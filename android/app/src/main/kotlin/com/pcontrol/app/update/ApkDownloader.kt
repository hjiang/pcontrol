package com.pcontrol.app.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads an APK from a URL into app-private cache storage.
 *
 * Files are stored under `cacheDir/updates/pcontrol-<version>.apk`.
 * Stale APKs in the same folder are cleaned up before download.
 * On failure (network error, partial write) the partial file is removed.
 */
class ApkDownloader(
    private val cacheDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : Downloader {
    companion object {
        private const val UPDATES_SUBDIR = "updates"
    }

    /**
     * Downloads the APK from [url] and returns the [File] handle,
     * or `null` on failure.
     *
     * @param url The browser_download_url of the APK asset.
     * @param version The normalized version string (e.g. "1.2.3") used in the filename.
     */
    override fun download(url: String, version: String): File? {
        val updatesDir = File(cacheDir, UPDATES_SUBDIR).also { it.mkdirs() }

        // Clean up any stale APKs from previous downloads
        cleanupStaleApks(updatesDir, version)

        val destFile = File(updatesDir, "pcontrol-$version.apk")

        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body
            val contentLength = body.contentLength()

            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Long = 0
                val input = body.byteStream()

                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    output.write(buffer, 0, n)
                    bytesRead += n
                }

                // If we know the expected size, verify we got it all
                if (contentLength > 0 && bytesRead != contentLength) {
                    destFile.delete()
                    return null
                }
            }

            destFile
        } catch (e: Exception) {
            destFile.delete()
            null
        }
    }

    /**
     * Removes any APK files in [dir] that don't match the current [version],
     * to prevent accumulation of superseded downloads.
     */
    private fun cleanupStaleApks(dir: File, currentVersion: String) {
        val currentName = "pcontrol-$currentVersion.apk"
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".apk") && file.name != currentName) {
                file.delete()
            }
        }
    }
}
