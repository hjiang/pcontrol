package com.pcontrol.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncClientTest {

    @Test
    fun `sync total call timeout bounds a server that never responds`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val client = SyncClient(
            serverUrl = "http://127.0.0.1:${server.port}",
            deviceToken = "test-token",
            callTimeoutSeconds = 1
        )

        try {
            val started = System.nanoTime()
            val response = runBlocking {
                client.sync(SyncRequest("2026-07-12T00:00:00Z", 1, emptyList()))
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertNull(response)
            assertNotNull("server should receive the timed-out request", server.takeRequest(1, TimeUnit.SECONDS))
            assertTrue("call should be bounded, elapsed=${elapsedMs}ms", elapsedMs < 3_000)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cancelling sync cancels the OkHttp call`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val callFailed = CountDownLatch(1)
        val httpClient = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) {
                    callFailed.countDown()
                }
            })
            .build()
        val client = SyncClient(
            serverUrl = "http://127.0.0.1:${server.port}",
            deviceToken = "test-token",
            httpClient = httpClient
        )

        try {
            val job = launch(Dispatchers.IO) {
                client.sync(SyncRequest("2026-07-12T00:00:00Z", 1, emptyList()))
            }
            assertNotNull("server should receive request", server.takeRequest(2, TimeUnit.SECONDS))

            job.cancelAndJoin()

            assertTrue("cancelling coroutine should cancel HTTP call", callFailed.await(2, TimeUnit.SECONDS))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `sync sends events and receives policy`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {
                "accepted_event_ids": ["u1", "u2"],
                "policy": {
                    "version": 2,
                    "total_daily_limit_minutes": 120,
                    "warn_threshold_percent": 90,
                    "limits": [],
                    "exclusions": []
                }
            }
        """.trimIndent()))
        server.start()

        val client = SyncClient(
            serverUrl = "http://127.0.0.1:${server.port}",
            deviceToken = "test-token"
        )

        val request = SyncRequest(
            deviceTime = "2026-07-03T15:00:00Z",
            policyVersion = 1,
            events = listOf(
                SyncEvent(
                    eventId = "u1",
                    kind = "app",
                    subject = "com.game",
                    label = "Game",
                    day = "2026-07-03",
                    startedAt = "2026-07-03T14:58:00Z",
                    durationSeconds = 120
                )
            )
        )

        val response = runBlocking { client.sync(request) }

        assertNotNull("expected non-null response", response)
        assertEquals(listOf("u1", "u2"), response!!.acceptedEventIds)
        assertNotNull("expected policy in response", response.policy)
        assertEquals(2, response.policy!!.version)
        assertEquals(120, response.policy!!.totalDailyLimitMinutes)

        server.shutdown()
    }

    @Test
    fun `sync returns null on network error`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()

        val client = SyncClient(
            serverUrl = "http://127.0.0.1:${server.port}",
            deviceToken = "test-token"
        )

        val request = SyncRequest(
            deviceTime = "2026-07-03T15:00:00Z",
            policyVersion = 1,
            events = emptyList()
        )

        val response = runBlocking { client.sync(request) }
        assertNull("expected null on error", response)

        server.shutdown()
    }

    @Test
    fun `sync sets bearer token header`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"accepted_event_ids":[],"policy":null}
        """.trimIndent()))
        server.start()

        val client = SyncClient(
            serverUrl = "http://127.0.0.1:${server.port}",
            deviceToken = "my-secret-token"
        )

        val request = SyncRequest(
            deviceTime = "2026-07-03T15:00:00Z",
            policyVersion = 1,
            events = emptyList()
        )

        runBlocking { client.sync(request) }

        val recordedRequest = server.takeRequest()
        assertEquals("Bearer my-secret-token", recordedRequest.getHeader("Authorization"))

        server.shutdown()
    }

    @Test
    fun `sync request with battery encodes both fields`() {
        val json = Json { ignoreUnknownKeys = true }
        val request = SyncRequest(
            deviceTime = "2026-07-06T00:00:00Z",
            policyVersion = 1,
            events = emptyList(),
            batteryPercent = 77,
            batteryCharging = false
        )
        val encoded = json.encodeToString(request)
        assertTrue("expected battery_percent", encoded.contains("\"battery_percent\":77"))
        assertTrue("expected battery_charging", encoded.contains("\"battery_charging\":false"))
    }

    @Test
    fun `sync request without battery omits both fields`() {
        val json = Json { ignoreUnknownKeys = true }
        val request = SyncRequest(
            deviceTime = "2026-07-06T00:00:00Z",
            policyVersion = 1,
            events = emptyList()
        )
        val encoded = json.encodeToString(request)
        assertFalse("expected no battery_percent", encoded.contains("battery_percent"))
        assertFalse("expected no battery_charging", encoded.contains("battery_charging"))
    }
}
