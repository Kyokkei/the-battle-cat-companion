package com.projectnyanko.companion.ui.event

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.projectnyanko.companion.data.event.EventScheduleRepository
import com.projectnyanko.companion.domain.event.EventKind
import com.projectnyanko.companion.domain.event.EventSchedule
import com.projectnyanko.companion.domain.event.EventStatus
import com.projectnyanko.companion.domain.event.RecurringEvent
import com.projectnyanko.companion.domain.event.ScheduledEvent
import com.projectnyanko.companion.ui.theme.Accent
import com.projectnyanko.companion.ui.theme.AccentDeep
import com.projectnyanko.companion.ui.theme.Ink
import com.projectnyanko.companion.ui.theme.InkSoft
import com.projectnyanko.companion.ui.theme.PaperLight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live and upcoming events, read from the schedule the wiki publishes.
 *
 * The bundle cannot refresh itself (that wiki is behind a Cloudflare check), so the screen says which
 * snapshot it is showing instead of pretending to be live.
 */
@Composable
fun EventsScreen(repository: EventScheduleRepository) {
    val schedule = remember { repository.load() }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) }

    if (schedule == null) {
        Text(
            text = "No event schedule in this build.",
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft,
            modifier = Modifier.padding(20.dp),
        )
        return
    }

    val gacha = schedule.ordered(EventKind.GACHA, today)
    val special = schedule.ordered(EventKind.SPECIAL, today)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(com.projectnyanko.companion.ui.theme.Paper),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Text(
                text = "Events",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Schedule from the wiki, fetched ${schedule.fetched}",
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
            )
            Spacer(Modifier.height(12.dp))
        }
        section("Gacha", gacha.size)
        items(gacha) { event -> EventRow(event, today) }
        section("Special Events", special.size)
        items(special) { event -> EventRow(event, today) }
        section("Recurring", schedule.recurring.size)
        items(schedule.recurring) { event -> RecurringRow(event) }
        item {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Recurring stages return on a fixed cycle; the exact window is set by the game's daily reset.",
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, count: Int) {
    item {
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (count > 0) "$title · $count" else title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = AccentDeep,
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun EventRow(event: ScheduledEvent, today: String) {
    val status = event.status(today)
    if (status == EventStatus.ENDED) return
    val line = when (status) {
        EventStatus.LIVE -> {
            val days = event.daysLeft(today)
            when {
                days == null -> "Live now"
                days <= 0L -> "Ends today"
                days == 1L -> "Ends tomorrow"
                else -> "Ends in $days days"
            }
        }
        else -> {
            val days = event.daysUntilStart(today)
            when {
                days == null -> "Coming up"
                days <= 0L -> "Starts today"
                days == 1L -> "Starts tomorrow"
                else -> "Starts in $days days"
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PaperLight)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = event.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = if (status == EventStatus.LIVE) AccentDeep else InkSoft,
            )
        }
        if (status == EventStatus.LIVE) {
            Text(
                text = "LIVE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Ink,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Accent)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun RecurringRow(event: RecurringEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PaperLight)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = event.name,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${event.cadenceLabel} · ${event.window}",
            style = MaterialTheme.typography.bodySmall,
            color = InkSoft,
        )
    }
}
