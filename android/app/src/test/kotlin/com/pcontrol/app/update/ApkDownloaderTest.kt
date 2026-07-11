package com.pcontrol.app.update

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ApkDownloaderTest {

    @Test
    fun `download writes exact bytes to file`() {
        val server = MockWebServer()
        val content = "Hello, APK download test!"
        server.enqueue(MockResponse().setResponseCode(200).setBody(content))
        server.start()

        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val downloader = ApkDownloader(cacheDir)

        val result = downloader.download(
            url = "http://127.0.0.1:${server.port}/apk",
            version = "1.2.3"
        )

        assertNotNull("expected non-null file", result)
        assertTrue("file should exist", result!!.exists())
        assertEquals("file should match content length", content.length.toLong(), result.length())

        // Clean up
        result.delete()
        server.shutdown()
    }

    @Test
    fun `download returns null on failed response`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()

        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val downloader = ApkDownloader(cacheDir)

        val result = downloader.download(
            url = "http://127.0.0.1:${server.port}/apk",
            version = "1.2.3"
        )

        assertNull("expected null on 500", result)

        server.shutdown()
    }

    @Test
    fun `download removes partial file on connection failure`() {
        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val updatesDir = File(cacheDir, "updates").also { it.mkdirs() }
        val destFile = File(updatesDir, "pcontrol-1.2.3.apk")

        // Create a pre-existing stale file
        destFile.writeText("stale")
        assertTrue("pre-existing file should exist", destFile.exists())

        // Use a downloader with very short timeouts pointed at a non-routable address
        val fastFailClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val downloader = ApkDownloader(cacheDir, client = fastFailClient)
        val result = downloader.download(
            url = "http://10.0.0.0/nonexistent.apk",
            version = "1.2.3"
        )

        assertNull("expected null on network error", result)
        assertFalse("partial file should be removed on failure", destFile.exists())

        // Clean up
        updatesDir.deleteRecursively()
    }

    @Test
    fun `download overwrites pre-existing file`() {
        val server = MockWebServer()
        val content = "new-content"
        server.enqueue(MockResponse().setResponseCode(200).setBody(content))
        server.start()

        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val updatesDir = File(cacheDir, "updates").also { it.mkdirs() }
        val staleFile = File(updatesDir, "pcontrol-1.2.3.apk")
        staleFile.writeText("old-content")

        val downloader = ApkDownloader(cacheDir)
        val result = downloader.download(
            url = "http://127.0.0.1:${server.port}/apk",
            version = "1.2.3"
        )

        assertNotNull("expected non-null file", result)
        assertEquals("file should contain fresh content", content.length.toLong(), result!!.length())

        // Clean up
        result.delete()
        server.shutdown()
    }

    @Test
    fun `download cleans up stale APKs from other versions`() {
        val server = MockWebServer()
        val content = "x"
        server.enqueue(MockResponse().setResponseCode(200).setBody(content))
        server.start()

        val cacheDir = RuntimeEnvironment.getApplication().cacheDir
        val updatesDir = File(cacheDir, "updates").also { it.mkdirs() }
        val staleFile = File(updatesDir, "pcontrol-0.9.0.apk")
        staleFile.writeText("stale-old-version")

        val downloader = ApkDownloader(cacheDir)
        val result = downloader.download(
            url = "http://127.0.0.1:${server.port}/apk",
            version = "1.2.3"
        )

        assertNotNull("expected non-null file", result)
        assertFalse("stale APK should be removed", staleFile.exists())
        assertTrue("new file should exist", result!!.exists())

        // Clean up
        result.delete()
        server.shutdown()
    }
}
