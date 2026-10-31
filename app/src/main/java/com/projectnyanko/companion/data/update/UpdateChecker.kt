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
 * Asks GitHub what the newest release is.
 *
 * Deliberately *not* a hand-maintained manifest file. A manifest is one more thing to remember to
 * edit on every release, and the one time you forget it the app silently stops telling anyone about
 * updates — which is exactly the failure this replaced. GitHub's own releases endpoint is already
 * the source of truth, so the app reads that and there is nothing to keep in sync.
 *
 * Nothing is downloaded here: the dialog sends the player to the release page and lets them pick an
 * APK, so the app never needs the install-packages permission.
 */
object UpdateChecker {
    /** `releases/latest` skips drafts and pre-releases, which is what we want. */
    const val RELEASES_API =
        "https://api.github.com/repos/Kyokkei/the-battle-cat-companion/releases/latest"

    private const val TIMEOUT_MS = 8_000

    fun isConfigured(): Boolean = RELEASES_API.isNotBlank()

    fun check(currentVersion: String): UpdateInfo? {
        if (!isConfigured()) return null
        return runCatching {
            val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "ProjectNyanko/1.0")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parseRelease(body, currentVersion)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /**
     * Reads `tag_name` / `html_url` / `body` out of a GitHub release payload.
     *
     * Kept separate from the network call so it can be tested against a real payload without one.
     */
    internal fun parseRelease(json: String, currentVersion: String): UpdateInfo? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        // GitHub tags are `v0.1.3`; the version comparator only understands numbers.
        val tag = root.optString("tag_name").trim().removePrefix("v").removePrefix("V")
        val url = root.optString("html_url").trim()
        if (tag.isEmpty() || url.isEmpty()) return null
        if (!isNewer(tag, currentVersion)) return null
        return UpdateInfo(tag, url, summarise(root.optString("body")))
    }

    /**
     * The release body is markdown written for a browser. The dialog has room for a couple of lines,
     * so headings and rules are dropped and the text is cut at a sentence-ish boundary.
     */
    private fun summarise(body: String): String? {
        val cleaned = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("---") && !it.startsWith("```") }
            .take(4)
            .joinToString(" ")
            // Markdown that survives a line filter: emphasis, inline code, links and bullets.
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace("**", "")
            .replace("`", "")
            .replace("*", "")
            .removePrefix("- ")
            .trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length <= 220) cleaned else cleaned.take(217).trimEnd() + "…"
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
