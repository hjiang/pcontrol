package com.pcontrol.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

// --- JSON structures matching the server API ---

@Serializable
data class SyncRequest(
    @kotlinx.serialization.SerialName("device_time")
    val deviceTime: String,
    @kotlinx.serialization.SerialName("policy_version")
    val policyVersion: Int,
    val events: List<SyncEvent>,
    @kotlinx.serialization.SerialName("battery_percent")
    val batteryPercent: Int? = null,
    @kotlinx.serialization.SerialName("battery_charging")
    val batteryCharging: Boolean? = null
)

@Serializable
data class SyncEvent(
    @kotlinx.serialization.SerialName("event_id")
    val eventId: String,
    val kind: String,
    val subject: String,
    val label: String,
    val day: String,
    @kotlinx.serialization.SerialName("started_at")
    val startedAt: String,
    @kotlinx.serialization.SerialName("duration_seconds")
    val durationSeconds: Int
)

@Serializable
data class SyncResponse(
    @kotlinx.serialization.SerialName("accepted_event_ids")
    val acceptedEventIds: List<String>,
    val policy: PolicyResponse? = null
)

@Serializable
data class PolicyResponse(
    val version: Int,
    @kotlinx.serialization.SerialName("total_daily_limit_minutes")
    val totalDailyLimitMinutes: Int? = null,
    @kotlinx.serialization.SerialName("warn_threshold_percent")
    val warnThresholdPercent: Int = 90,
    val limits: List<LimitJson> = emptyList(),
    val exclusions: List<ExclusionJson> = emptyList()
)

@Serializable
data class LimitJson(
    val kind: String,
    val subject: String,
    @kotlinx.serialization.SerialName("daily_limit_minutes")
    val dailyLimitMinutes: Int
)

@Serializable
data class ExclusionJson(
    val kind: String,
    val subject: String
)

// --- SyncClient ---

class SyncClient(
    private val serverUrl: String,
    private val deviceToken: String,
    callTimeoutSeconds: Long = 20L,
    httpClient: OkHttpClient? = null
) {
    private val client = httpClient ?: buildClient(callTimeoutSeconds)

    init {
        require(callTimeoutSeconds > 0L) { "Call timeout must be positive" }
    }

    companion object {
        private fun buildClient(callTimeoutSeconds: Long): OkHttpClient {
            require(callTimeoutSeconds > 0L) { "Call timeout must be positive" }
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                .build()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Sends a sync request to the server.
     * @return SyncResponse on success, null on network/HTTP error.
     */
    suspend fun sync(request: SyncRequest): SyncResponse? {
        return try {
            val body = json.encodeToString(request)
            val requestBody = body.toRequestBody(jsonMediaType)

            val httpRequest = Request.Builder()
                .url("$serverUrl/api/v1/sync")
                .header("Authorization", "Bearer $deviceToken")
                .post(requestBody)
                .build()

            val responseBody = awaitResponseBody(httpRequest) ?: return null
            json.decodeFromString<SyncResponse>(responseBody)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun awaitResponseBody(request: Request): String? =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val resumeToken = continuation.tryResume(null)
                    if (resumeToken != null) continuation.completeResume(resumeToken)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.use {
                        if (it.isSuccessful) {
                            try {
                                it.body?.string()
                            } catch (_: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                    }
                    val resumeToken = continuation.tryResume(body)
                    if (resumeToken != null) continuation.completeResume(resumeToken)
                }
            })
        }
}
