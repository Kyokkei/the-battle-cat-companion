package com.projectnyanko.companion.data.event

import android.content.Context
import com.projectnyanko.companion.domain.event.EventSchedule
import com.projectnyanko.companion.domain.event.EventScheduleParser
import com.projectnyanko.companion.domain.event.StageClocks

/**
 * The event schedule that ships in the APK.
 *
 * It comes from the wiki's own machine-readable file (`Template:CurrentEvents/Schedule.json`), which
 * its editors keep current — the app cannot fetch it at runtime, because that wiki sits behind a
 * Cloudflare check that only a browser can pass. A stale bundle therefore shows stale events, which
 * is why the screen prints the snapshot date it was built from.
 */
class EventScheduleRepository(private val context: Context) {
    @Volatile
    private var cached: EventSchedule? = null

    @Volatile
    private var cachedBanners: Map<String, String>? = null

    @Volatile
    private var cachedClocks: StageClocks? = null

    fun load(): EventSchedule? = cached ?: runCatching {
        context.assets.open(ASSET).bufferedReader().use { EventScheduleParser.parse(it.readText()) }
    }.getOrNull()?.also { cached = it }

    /**
     * The weekly rotation, with real windows instead of bare names.
     *
     * Battle Cats schedules against the device clock, so this is what answers "what is on right now";
     * [EventSchedule.recurring] only carries names and a duration.
     */
    fun clocks(): StageClocks = cachedClocks ?: runCatching {
        context.assets.open(CLOCK_ASSET).bufferedReader().use { StageClocks.parse(it.readText()) }
    }.onFailure {
        android.util.Log.w("EventSchedule", "clock table failed: ${it.message}", it)
    }.getOrDefault(StageClocks(emptyList())).also { cachedClocks = it }

    /**
     * Artwork URL for a banner, or null when the bundled map has never heard of it.
     *
     * The map (379 banners) ships with the schedule; the PNGs themselves are downloaded on demand by
     * [BannerImageLoader]. The image host answers plain GETs, unlike the wiki it belongs to.
     */
    fun bannerUrl(name: String): String? = bannerMap()[name]

    private fun bannerMap(): Map<String, String> = cachedBanners ?: runCatching {
        val json = context.assets.open(BANNER_MAP).bufferedReader().use { it.readText() }
        val root = org.json.JSONObject(json).optJSONObject("banners") ?: return@runCatching emptyMap()
        buildMap {
            for (key in root.keys()) {
                val url = root.optJSONObject(key)?.optString("url").orEmpty()
                if (url.isNotEmpty()) put(key, url)
            }
        }
    }.onFailure {
        android.util.Log.w("EventSchedule", "banner map failed: ${it.message}", it)
    }.getOrDefault(emptyMap()).also { cachedBanners = it }

    private companion object {
        const val ASSET = "events/schedule.json"
        const val BANNER_MAP = "events/banners.json"
        const val CLOCK_ASSET = "events/clocks.json"
    }
}
