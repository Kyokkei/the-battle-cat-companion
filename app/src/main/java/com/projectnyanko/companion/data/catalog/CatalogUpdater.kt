package com.projectnyanko.companion.data.catalog

import android.content.Context
import android.util.Log
import com.projectnyanko.companion.domain.seed.GameRegion
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pulls a fresh gacha catalog from the same upstream snapshots the app ships with.
 *
 * The bundled JSON goes stale the moment PONOS adds a banner: the app then lists yesterday's
 * events and, worse, hides the guaranteed track for a banner it has never heard of. Waiting for a
 * new APK to fix that is silly when the data is three small files.
 *
 * Deliberately conservative:
 *
 * - **Never downgrades.** A download only replaces the cached copy if it parses and carries at
 *   least as many events as the copy it would replace, so a bad fetch or an upstream hiccup can
 *   not silently empty the app's catalog.
 * - **Atomic.** Written to a `.part` file and renamed, so a killed download leaves the old copy
 *   intact instead of a half-written JSON.
 * - **Cheap.** Once per [REFRESH_INTERVAL_MS]; failures still stamp the attempt so a device with
 *   no signal does not retry on every launch.
 * - **Silent.** Any failure just means the bundled snapshot keeps being used.
 */
class CatalogUpdater(context: Context) {
    private val appContext = context.applicationContext

    /** Returns true when at least one file was replaced and the catalog needs re-reading. */
    fun refreshIfStale(): Boolean {
        // ~1.7 MB for both regions. Worth it on Wi-Fi, rude on someone's mobile data, so an
        // unmetered connection is required and a metered one is simply left for later.
        if (!onUnmeteredNetwork()) {
            Log.i(TAG, "skipping refresh: connection is metered")
            return false
        }
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
        if (now - last < REFRESH_INTERVAL_MS) return false
        prefs.edit().putLong(KEY_LAST_ATTEMPT, now).apply()

        var changed = false
        for (region in GameRegion.values()) {
            val folder = region.name.lowercase()
            for (name in FILES) {
                changed = download(region, folder, name) or changed
            }
        }
        Log.i(TAG, "catalog refresh finished, changed=$changed")
        return changed
    }

    private fun onUnmeteredNetwork(): Boolean = runCatching {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    private fun download(region: GameRegion, folder: String, name: String): Boolean {
        val target = File(cacheDir(region), name)
        val incoming = fetch("$BASE_URL/$folder/$name") ?: return false
        if (incoming.isEmpty()) return false

        // Reject anything that would make the catalog worse than what is already there.
        val currentCount = countEvents(if (target.isFile) target.readBytes() else null)
            ?: countEvents(assetBytes(region, name))
        val incomingCount = countEvents(incoming)
        if (!CatalogRules.shouldReplace(currentCount, incomingCount)) {
            Log.w(TAG, "$name from upstream rejected (local=$currentCount upstream=$incomingCount)")
            return false
        }
        if (target.isFile && target.length() == incoming.size.toLong()) {
            // Same size is not proof of identical content, but re-writing identical bytes on every
            // check is pointless churn; the parser is deterministic over this file.
            return false
        }

        return runCatching {
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, "$name.part")
            part.writeBytes(incoming)
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) {
                    part.delete()
                    return@runCatching false
                }
            }
            Log.i(TAG, "refreshed ${region.name} $name (${incoming.size} bytes)")
            true
        }.getOrElse { error ->
            Log.w(TAG, "could not store $name: ${error.message}")
            false
        }
    }

    private fun fetch(url: String): ByteArray? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "$url returned ${connection.responseCode}")
                return null
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }.getOrElse { error ->
        Log.w(TAG, "fetch failed for $url: ${error.message}")
        null
    }

    private fun countEvents(bytes: ByteArray?): Int? = CatalogRules.countEvents(bytes)

    internal fun assetBytes(region: GameRegion, name: String): ByteArray? = runCatching {
        appContext.assets.open("neko/data/${region.name.lowercase()}/$name").use { it.readBytes() }
    }.getOrNull()

    private fun cacheDir(region: GameRegion): File =
        File(File(appContext.filesDir, CACHE_DIR), region.name.lowercase())

    /** Where a downloaded copy of [name] lives for [region], if one exists. */
    fun cachedFile(region: GameRegion, name: String): File =
        File(cacheDir(region), name)

    private companion object {
        const val TAG = "CatalogUpdater"

        /** Same repository the bundled snapshots came from, read straight from the default branch. */
        const val BASE_URL = "https://raw.githubusercontent.com/IliyanGavrilov/NekoOptimizer/main/neko/data"
        const val CACHE_DIR = "catalog"
        const val PREFS = "catalog-refresh"
        const val KEY_LAST_ATTEMPT = "last-attempt"
        const val REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L
        const val TIMEOUT_MS = 15_000

        val FILES = listOf("gacha_events.json", "gacha_pools.json", "units.json")
    }
}

/**
 * The decisions that guard a refresh, kept separate from the downloading so they can be tested
 * without a network or a Context.
 */
object CatalogRules {
    /**
     * Entry count for a catalog file, or null when the bytes are not a catalog at all.
     *
     * Two shapes arrive from upstream: `gacha_events.json` is a JSON array of events, while
     * `gacha_pools.json` is an object keyed by pool id. Reading only the array shape silently
     * rejected every pool download, which is a bug that would have shipped a refreshed event list
     * pointing at pools the app did not have.
     */
    fun countEvents(bytes: ByteArray?): Int? {
        if (bytes == null || bytes.isEmpty()) return null
        val text = String(bytes, Charsets.UTF_8).trim()
        if (text.isEmpty()) return null
        return runCatching {
            when (text.first()) {
                '[' -> org.json.JSONArray(text).length()
                '{' -> org.json.JSONObject(text).length()
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Whether a download may replace the local copy.
     *
     * Anything that does not parse is refused, and so is anything smaller than what it would
     * replace: a truncated download or an upstream mistake must never leave the app with fewer
     * banners than it shipped with.
     */
    fun shouldReplace(current: Int?, incoming: Int?): Boolean {
        if (incoming == null || incoming <= 0) return false
        if (current == null) return true
        return incoming >= current
    }
}
