@file:Suppress("UNUSED_VARIABLE")

package com.pcontrol.app.update

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GitHubReleaseClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `fetchLatestRelease returns correct UpdateInfo from releases latest`() {
        val server = MockWebServer()
        val release = GitHubRelease(
            tagName = "android-v1.2.3",
            name = "v1.2.3",
            assets = listOf(
                GitHubAsset(name = "pcontrol-v1.2.3.apk", size = 4_500_000, downloadUrl = "https://example.com/apk"),
                GitHubAsset(name = "checksums.txt", size = 200, downloadUrl = "https://example.com/checksums")
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(release)))
        server.start()

        val client = GitHubReleaseClient(
            repo = "test/repo",
            baseUrl = "http://127.0.0.1:${server.port}",
            client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )

        val result = client.fetchLatestRelease()

        assertNotNull("expected non-null result", result)
        assertEquals("1.2.3", result!!.version)
        assertEquals("https://example.com/apk", result.downloadUrl)
        assertEquals(4_500_000L, result.sizeBytes)

        server.shutdown()
    }

    @Test
    fun `fetchLatestRelease returns null when no apk asset`() {
        val server = MockWebServer()
        val release = GitHubRelease(
            tagName = "v1.0.0",
            assets = listOf(
                GitHubAsset(name = "checksums.txt", size = 200, downloadUrl = "https://example.com/checksums")
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(release)))
        server.start()

        val client = GitHubReleaseClient(
            repo = "test/repo",
            baseUrl = "http://127.0.0.1:${server.port}",
            client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )

        val result = client.fetchLatestRelease()
        assertNull("expected null when no APK asset", result)

        server.shutdown()
    }

    @Test
    fun `fetchLatestRelease returns null on 404`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()

        val client = GitHubReleaseClient(
            repo = "test/repo",
            baseUrl = "http://127.0.0.1:${server.port}",
            client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )

        val result = client.fetchLatestRelease()
        assertNull("expected null on 404", result)

        server.shutdown()
    }

    @Test
    fun `fetchLatestRelease returns null on network exception`() {
        // No server running — connection refused
        val client = GitHubReleaseClient(
            repo = "test/repo",
            client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )

        val result = client.fetchLatestRelease()
        assertNull("expected null on network error", result)
    }
}
