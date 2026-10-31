package com.projectnyanko.companion.data.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A newer build the app knows about. */
data class UpdateInfo(
    val version: String,
    val url: String,
    val notes: String?,
)

/**
 * Checks a small JSON manifest for a newer version.
 *
 * The manifest is whatever the project publishes:
 *
 * ```
 * { "version": "0.2.0", "url": "https://.../app-release.apk", "notes": "…" }
 * ```
 *
 * Nothing is downloaded here — the dialog sends the player to [UpdateInfo.url] and lets Android's own
 * installer do the work, so the app never needs the install-packages permission.
 */
object UpdateChecker {
    /** Fill this in once the release manifest is hosted. Empty means "never check". */
    const val MANIFEST_URL = ""

    private const val TIMEOUT_MS = 8_000

    fun isConfigured(): Boolean = MANIFEST_URL.isNotBlank()

    fun check(currentVersion: String): UpdateInfo? {
        if (!isConfigured()) return null
        return runCatching {
            val connection = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "ProjectNyanko/1.0")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val version = json.optString("version").trim()
                val url = json.optString("url").trim()
                if (version.isEmpty() || url.isEmpty()) return@runCatching null
                if (!isNewer(version, currentVersion)) return@runCatching null
                UpdateInfo(version, url, json.optString("notes").trim().ifEmpty { null })
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /** `0.2.0` beats `0.1.9`; anything unparsable is treated as "not newer" rather than nagging. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').mapNotNull { it.toIntOrNull() }
        val b = current.split('.').mapNotNull { it.toIntOrNull() }
        if (a.isEmpty() || b.isEmpty()) return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}
