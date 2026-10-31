package com.projectnyanko.companion.data.event

import android.content.Context
import com.projectnyanko.companion.domain.event.EventSchedule
import com.projectnyanko.companion.domain.event.EventScheduleParser

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

    fun load(): EventSchedule? = cached ?: runCatching {
        context.assets.open(ASSET).bufferedReader().use { EventScheduleParser.parse(it.readText()) }
    }.getOrNull()?.also { cached = it }

    private companion object {
        const val ASSET = "events/schedule.json"
    }
}
