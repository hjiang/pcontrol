package com.pcontrol.app.update

import com.pcontrol.core.Version
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The result of fetching and evaluating the latest GitHub release.
 *
 * @property version The normalized semver string of the latest release (e.g. "1.2.3").
 * @property downloadUrl The URL to download the APK.
 * @property sizeBytes The reported size of the APK asset in bytes.
 */
data class UpdateInfo(
    val version: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

// ── GitHub REST API DTOs ──────────────────────────────────────────────

@Serializable
data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String = "",
    val name: String = "",
    val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url")
    val downloadUrl: String = ""
)

/**
 * Fetches release info from the GitHub Releases API and picks the
 * first APK asset.
 *
 * Design: Uses the same [OkHttpClient]-based pattern as [SyncClient].
 * Unauthenticated calls are rate-limited to 60/hour — our once-per-day
 * cadence stays well under that. The manual "Check for updates" button
 * calls [UpdateCoordinator.runOnce] with `force = true`, bypassing the
 * 24h gate; the practical rate-limit protection is the GitHub API's own
 * 60/hr limit.
 */
class GitHubReleaseClient(
    private val repo: String = "hjiang/pcontrol",
    private val baseUrl: String = "https://api.github.com",
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) : UpdateClient {

    /**
     * Fetches the latest release and returns [UpdateInfo] if a suitable
     * APK asset is found, or `null` on network error / missing asset.
     */
    override fun fetchLatestRelease(): UpdateInfo? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "pcontrol")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null

                val body = response.body?.string() ?: return@use null
                val release = json.decodeFromString<GitHubRelease>(body)

                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@use null
                val version = Version.parse(release.tagName) ?: return@use null

                UpdateInfo(
                    version = version,
                    downloadUrl = apkAsset.downloadUrl,
                    sizeBytes = apkAsset.size
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
