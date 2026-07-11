package com.pcontrol.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UpdateCoordinatorTest {

    private lateinit var context: android.content.Context
    private lateinit var state: UpdateState

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        state = UpdateState(context)
        // Reset state
        state.autoUpdateEnabled = true
        state.lastUpdateCheckMs = 0L
        state.downloadedApkPath = ""
    }

    // ── Fake seams ───────────────────────────────────────────────────

    private class FakeClient(private val updateInfo: UpdateInfo?) : UpdateClient {
        var fetchCalled = false
        override fun fetchLatestRelease(): UpdateInfo? {
            fetchCalled = true
            return updateInfo
        }
    }

    private class FakeDownloader(private val shouldSucceed: Boolean) : Downloader {
        var downloadCalled = false
        var capturedUrl: String? = null
        var capturedVersion: String? = null
        var capturedExpectedSize: Long = 0L
        override fun download(url: String, version: String, expectedSize: Long): File? {
            downloadCalled = true
            capturedUrl = url
            capturedVersion = version
            capturedExpectedSize = expectedSize
            if (!shouldSucceed) return null
            return File.createTempFile("downloaded", ".apk").also {
                it.writeText("fake apk")
                it.deleteOnExit()
            }
        }
    }

    private class FakeVerifier(private val shouldMatch: Boolean) : Verifier {
        var verifyCalled = false
        override fun matchesInstalled(context: android.content.Context, apkFile: File): Boolean {
            verifyCalled = true
            return shouldMatch
        }
    }

    private class FakeInstaller(private val shouldSucceed: Boolean) : Installer {
        var installCalled = false
        var capturedFile: File? = null
        override fun install(context: android.content.Context, apkFile: File): Boolean {
            installCalled = true
            capturedFile = apkFile
            return shouldSucceed
        }
    }

    // ── Tests ────────────────────────────────────────────────────────

    @Test
    fun `disabled returns DISABLED immediately`() {
        state.autoUpdateEnabled = false
        val client = FakeClient(null)
        val coordinator = UpdateCoordinator(
            context = context,
            updateState = state,
            versionName = "1.0.0",
            client = client
        )
        val result = coordinator.runOnce(force = true)
        assertEquals(UpdateResult.DISABLED, result)
        assertFalse("client should not be called when disabled", client.fetchCalled)
    }

    @Test
    fun `newer version triggers download verify and install`() {
        val client = FakeClient(
            UpdateInfo(version = "1.2.0", downloadUrl = "https://example.com/apk", sizeBytes = 1000)
        )
        val downloader = FakeDownloader(shouldSucceed = true)
        val verifier = FakeVerifier(shouldMatch = true)
        val installer = FakeInstaller(shouldSucceed = true)

        val coordinator = UpdateCoordinator(
            context = context,
            updateState = state,
            versionName = "1.0.0",
            client = client,
            downloader = downloader,
            verifier = verifier,
            installer = installer
        )

        val result = coordinator.runOnce(force = true)
        assertEquals(UpdateResult.INSTALL_TRIGGERED, result)
        assertTrue("fetch should be called", client.fetchCalled)
        assertTrue("download should be called", downloader.downloadCalled)
        assertTrue("verify should be called", verifier.verifyCalled)
        assertTrue("install should be called", installer.installCalled)
        assertEquals("https://example.com/apk", downloader.capturedUrl)
        assertEquals("1.2.0", downloader.capturedVersion)
        assertTrue("check timestamp should be updated", state.lastUpdateCheckMs > 0)
    }

    @Test
    fun `same version returns UP_TO_DATE`() {
        val client = FakeClient(
            UpdateInfo(version = "1.0.0", downloadUrl = "https://example.com/apk", sizeBytes = 1000)
        )
        val downloader = FakeDownloader(shouldSucceed = true)
        val coordinator = UpdateCoordinator(
            context = context,
            updateState = state,
            versionName = "1.0.0",
            client = client,
            downloader = downloader
        )

        val result = coordinator.runOnce(force = true)
        assertEquals(UpdateResult.UP_TO_DATE, result)
        assertTrue("fetch should be called", client.fetchCalled)
        assertFalse("download should not be called", downloader.downloadCalled)
        assertTrue("check timestamp should be updated", state.lastUpdateCheckMs > 0)
    }

    @Test
    fun `network error returns NETWORK_ERROR and refreshes timestamp`() {
        val client = FakeClient(null)
        val coordinator = UpdateCoordinator(
            context = context,
            updateState = state,
            versionName = "1.0.0",
            client = client
        )

        val result = coordinator.runOnce(force = true)
        assertEquals(UpdateResult.NETWORK_ERROR, result)
        assertTrue("fetch should be called", client.fetchCalled)
        // Timestamp should still be refreshed to avoid hammering
        assertTrue("check timestamp should be updated", state.lastUpdateCheckMs > 0)
    }

    @Test
    fun `signature mismatch returns SIGNATURE_MISMATCH`() {
        val client = FakeClient(
            UpdateInfo(version = "1.2.0", downloadUrl = "https://example.com/apk", sizeBytes = 1000)
        )
        val downloader = FakeDownloader(shouldSucceed = true)
        val verifier = FakeVerifier(shouldMatch = false)
        val installer = FakeInstaller(shouldSucceed = false)

        val coordinator = UpdateCoordinator(
            context = context,
            updateState = state,
            versionName = "1.0.0",
            client = client,
            downloader = downloader,
            verifier = verifier,
            installer = installer
        )

        val result = coordinator.runOnce(force = true)
        assertEquals(UpdateResult.SIGNATURE_MISMATCH, result)
        assertTrue("fetch should be called", client.fetchCalled)
        assertTrue("download should be called", downloader.downloadCalled)
        assertTrue("verify should be called", verifier.verifyCalled)
        assertFalse("install should NOT be called on mismatch", installer.installCalled)
    }

    @Test
    fun `download fail returns DOWNLOAD_FAILED`() {
        val client = FakeClient(
            UpdateInfo(version = "1.2.0", downloadUrl = "https://example.com/apk", sizeBytes = 1000)
        )
        val downloader = FakeDownloader(shouldSucceed = false)

        val coordinator = UpdateCoordinator(
            context = context,
            updateState = state,
            versionName = "1.0.0",
            client = client,
            downloader = downloader
        )

        val result = coordinator.runOnce(force = true)
        assertEquals(UpdateResult.DOWNLOAD_FAILED, result)
        assertTrue("fetch should be called", client.fetchCalled)
        assertTrue("download should be called", downloader.downloadCalled)
    }

    @Test
    fun `instance version is parsed correctly`() {
        // Confirm that versionName "1.0.0" is used by the coordinator
        val client = FakeClient(
            UpdateInfo(version = "1.0.0", downloadUrl = "https://example.com/apk", sizeBytes = 1000)
        )
        val coordinator = UpdateCoordinator(
            context = context,
            updateState = state,
            versionName = "1.0.0",
            client = client
        )

        val result = coordinator.runOnce(force = true)
        assertEquals(UpdateResult.UP_TO_DATE, result)
    }
}
