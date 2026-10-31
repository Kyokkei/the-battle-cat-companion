package com.projectnyanko.companion.data.event

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads gacha banner art and keeps it on disk.
 *
 * The host is `static.wikitide.net`, which — unlike the wiki itself — answers plain HTTP with no
 * Cloudflare clearance, so a normal GET works. Only the banner rows the Events tab shows are fetched,
 * so this is a handful of ~100 KB PNGs, cached under `cacheDir/banners` and reused until the cache is
 * cleared. Every failure is silent: a missing picture must never break the row.
 */
object BannerImageLoader {
    private const val TIMEOUT_MS = 8_000

    fun load(context: Context, url: String): ImageBitmap? = runCatching {
        val file = cacheFile(context, url)
        if (file.isFile && file.length() > 0) return@runCatching decode(file)

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ProjectNyanko/1.0 (Battle Cats companion)")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            // Write to a temp file first: a half-downloaded PNG that gets decoded next launch would
            // fail forever, and the cache would never recover.
            val temp = File(file.parentFile, file.name + ".part")
            connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
            if (!temp.renameTo(file)) {
                temp.delete()
                return@runCatching null
            }
            decode(file)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun cacheFile(context: Context, url: String): File {
        val dir = File(context.cacheDir, "banners").apply { mkdirs() }
        return File(dir, url.hashCode().toString(16) + ".png")
    }

    private fun decode(file: File): ImageBitmap? =
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
}
