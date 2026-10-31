package com.projectnyanko.companion.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Downloads the release APK and hands it to Android's installer.
 *
 * "One tap" is as far as an ordinary app can go: the download and the hand-off are automatic, but
 * the final **Install** press belongs to the system — that dialog cannot be skipped without
 * device-owner privileges, and pretending otherwise would be a lie in a release note.
 *
 * The one extra tap that is unavoidable once: Android requires "install unknown apps" for this
 * package. [canInstall] reports whether that is already granted; when it is not, [openPermission]
 * sends the player straight to the right settings page.
 */
object UpdateInstaller {
    private const val TAG = "UpdateInstaller"
    private const val TIMEOUT_MS = 20_000
    private const val MIN_APK_BYTES = 1_000_000L

    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Sends the player to this app's "install unknown apps" toggle. */
    fun openPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "could not open the install-permission settings", it) }
    }

    /**
     * Streams [url] into `cache/updates` and returns the file once it is a real APK.
     *
     * A partial download is worse than no download: Android's installer would either refuse it or,
     * worse, fail with "app not installed" and no explanation. So the bytes go to a `.part` file,
     * get checked (zip with an `AndroidManifest.xml` inside), and only then take the real name.
     */
    fun download(context: Context, url: String, onProgress: (Int) -> Unit): File? {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        // Anything left from a previous attempt is stale by definition.
        directory.listFiles()?.forEach { it.delete() }
        val target = File(directory, "nyanko-update.apk")
        val part = File(directory, "nyanko-update.apk.part")

        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ProjectNyanko/1.0")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    Log.w(TAG, "download failed with HTTP ${connection.responseCode}")
                    return null
                }
                val total = connection.contentLength.toLong()
                var written = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val percent = ((written * 100) / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
                if (written < MIN_APK_BYTES) {
                    Log.w(TAG, "download was only $written bytes; discarding")
                    part.delete()
                    return null
                }
                if (!isReadableApk(part)) {
                    Log.w(TAG, "downloaded file is not an APK; discarding")
                    part.delete()
                    return null
                }
                if (!part.renameTo(target)) {
                    part.delete()
                    return null
                }
                onProgress(100)
                target
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            Log.w(TAG, "download failed: ${error.message}")
            part.delete()
            null
        }
    }

    /** Launches Android's installer for a file already in `cache/updates`. */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrElse { error ->
        Log.w(TAG, "could not launch the installer: ${error.message}")
        false
    }

    /** An APK is a zip whose root holds `AndroidManifest.xml`. Cheap, and catches HTML error pages. */
    private fun isReadableApk(file: File): Boolean = runCatching {
        ZipFile(file).use { zip -> zip.getEntry("AndroidManifest.xml") != null }
    }.getOrDefault(false)
}
