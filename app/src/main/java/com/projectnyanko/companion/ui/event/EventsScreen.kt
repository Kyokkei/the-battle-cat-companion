package com.projectnyanko.companion.ui.event

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.projectnyanko.companion.data.event.BannerImageLoader
import com.projectnyanko.companion.data.event.EventScheduleRepository
import com.projectnyanko.companion.domain.event.ClockSlot
import com.projectnyanko.companion.domain.event.EventKind
import com.projectnyanko.companion.domain.event.EventStatus
import com.projectnyanko.companion.domain.event.ScheduledEvent
import com.projectnyanko.companion.domain.event.StageClocks
import com.projectnyanko.companion.domain.event.clockTime
import com.projectnyanko.companion.domain.event.minutesLabel
import com.projectnyanko.companion.domain.event.weekdayName
import com.projectnyanko.companion.ui.theme.Accent
import com.projectnyanko.companion.ui.theme.AccentDeep
import com.projectnyanko.companion.ui.theme.Ink
import com.projectnyanko.companion.ui.theme.InkSoft
import com.projectnyanko.companion.ui.theme.Paper
import com.projectnyanko.companion.ui.theme.PaperLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class Section(val title: String) {
    GACHA("Gacha"),
    SPECIAL("Special Events"),
    CLOCKS("Today's stages"),
}

/**
 * The Event tab: three full-width tiles, then one screen per tile.
 *
 * The tiles stack instead of sitting in a 2 + 1 grid — with three of them, a grid reads like a
 * mistake, and a full-width row is both easier to hit and easier to read.
 */
@Composable
fun EventsScreen(repository: EventScheduleRepository) {
    val schedule = remember { repository.load() }
    val clocks = remember { repository.clocks() }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) }
    var open by remember { mutableStateOf<Section?>(null) }

    val gacha = schedule?.ordered(EventKind.GACHA, today).orEmpty()
    val special = schedule?.ordered(EventKind.SPECIAL, today).orEmpty()

    when (open) {
        null -> Bento(
            today = today,
            gacha = gacha,
            special = special,
            liveClocks = clocks.live(Calendar.getInstance()),
            fetched = schedule?.fetched.orEmpty(),
            onOpen = { open = it },
        )
        Section.GACHA -> GachaList(gacha, repository, today) { open = null }
        Section.SPECIAL -> SpecialList(special, today) { open = null }
        Section.CLOCKS -> ClockList(clocks) { open = null }
    }
}

@Composable
private fun Bento(
    today: String,
    gacha: List<ScheduledEvent>,
    special: List<ScheduledEvent>,
    liveClocks: List<ClockSlot>,
    fetched: String,
    onOpen: (Section) -> Unit,
) {
    val liveGacha = gacha.count { it.status(today) == EventStatus.LIVE }
    val liveSpecial = special.count { it.status(today) == EventStatus.LIVE }
    val soonestGacha = gacha.filter { it.status(today) == EventStatus.LIVE }.minByOrNull { it.end }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Events", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (fetched.isBlank()) "No schedule in this build" else "Wiki schedule · fetched $fetched",
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft,
        )
        Spacer(Modifier.height(16.dp))
        BentoTile(
            title = "Gacha",
            big = "$liveGacha live now",
            caption = soonestGacha?.let { "Next one ends in ${it.daysLeft(today) ?: 0} days" } ?: "Nothing live",
        ) { onOpen(Section.GACHA) }
        Spacer(Modifier.height(12.dp))
        BentoTile(
            title = "Special Events",
            big = "$liveSpecial live now",
            caption = special.firstOrNull { it.status(today) == EventStatus.LIVE }
                ?.let { "Next one ends in ${it.daysLeft(today) ?: 0} days" } ?: "Nothing live",
        ) { onOpen(Section.SPECIAL) }
        Spacer(Modifier.height(12.dp))
        BentoTile(
            title = "Today's stages",
            big = "${liveClocks.size} on right now",
            caption = "Weekly rotation, in your local time",
        ) { onOpen(Section.CLOCKS) }
    }
}

@Composable
private fun BentoTile(title: String, big: String, caption: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PaperLight)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AccentDeep)
            Spacer(Modifier.height(4.dp))
            Text(big, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(2.dp))
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = InkSoft)
        }
        Text("›", style = MaterialTheme.typography.headlineMedium, color = AccentDeep)
    }
}

@Composable
private fun ListHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "‹",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = AccentDeep,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = InkSoft)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AccentDeep)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun GachaList(
    events: List<ScheduledEvent>,
    repository: EventScheduleRepository,
    today: String,
    onBack: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().background(Paper), contentPadding = PaddingValues(16.dp)) {
        item {
            ListHeader("Gacha", "${events.count { it.status(today) == EventStatus.LIVE }} live now", onBack)
            Spacer(Modifier.height(12.dp))
        }
        items(events) { event ->
            EventCard(
                name = event.name,
                statusLine = when (event.status(today)) {
                    EventStatus.LIVE -> "Ends in ${event.daysLeft(today) ?: 0} days"
                    else -> "Starts in ${event.daysUntilStart(today) ?: 0} days"
                },
                live = event.status(today) == EventStatus.LIVE,
                bannerUrl = repository.bannerUrl(event.name),
            )
        }
    }
}

@Composable
private fun SpecialList(events: List<ScheduledEvent>, today: String, onBack: () -> Unit) {
    val live = events.filter { it.status(today) == EventStatus.LIVE }
    val upcoming = events.filter { it.status(today) == EventStatus.UPCOMING }
    LazyColumn(Modifier.fillMaxSize().background(Paper), contentPadding = PaddingValues(16.dp)) {
        item {
            ListHeader("Special Events", "${live.size} live now", onBack)
        }
        if (live.isNotEmpty()) {
            item { SectionLabel("On now") }
            items(live) { event -> SpecialCard(event, today, live = true) }
        }
        if (upcoming.isNotEmpty()) {
            item { SectionLabel("Coming up") }
            items(upcoming) { event -> SpecialCard(event, today, live = false) }
        }
    }
}

@Composable
private fun SpecialCard(event: ScheduledEvent, today: String, live: Boolean) {
    val days = if (live) event.daysLeft(today) else event.daysUntilStart(today)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PaperLight)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(event.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                days == null -> if (live) "Live now" else "Coming up"
                live && days <= 0 -> "Ends today"
                live && days == 1L -> "Ends tomorrow"
                live -> "Ends in $days days"
                days <= 0 -> "Starts today"
                days == 1L -> "Starts tomorrow"
                else -> "Starts in $days days"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (live) AccentDeep else InkSoft,
        )
    }
}

@Composable
private fun ClockList(clocks: StageClocks, onBack: () -> Unit) {
    val now = remember { Calendar.getInstance() }
    val live = remember { clocks.live(now) }
    val later = remember { clocks.laterToday(now) }
    val week = remember { clocks.restOfWeek(now) }

    LazyColumn(Modifier.fillMaxSize().background(Paper), contentPadding = PaddingValues(16.dp)) {
        item {
            ListHeader("Today's stages", "${now.weekdayName()} · ${now.clockTime()} local time", onBack)
        }
        item { SectionLabel(if (live.isEmpty()) "Nothing on right now" else "On now · ${live.size}") }
        items(live) { slot -> ClockCard(slot, now, live = true) }
        if (later.isNotEmpty()) {
            item { SectionLabel("Later today") }
            items(later) { slot -> ClockCard(slot, now, live = false) }
        }
        if (week.isNotEmpty()) {
            item { SectionLabel("Rest of the week") }
            items(week) { slot -> ClockCard(slot, now, live = false, showDay = true) }
        }
    }
}

@Composable
private fun ClockCard(slot: ClockSlot, now: Calendar, live: Boolean, showDay: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PaperLight)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = slot.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Ink,
                modifier = Modifier.weight(1f),
            )
            if (live) {
                Text(
                    text = "ON",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Accent)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                live -> "Ends in ${minutesLabel(slot.minutesLeft(now))} · ${slot.end.clockTime()}"
                showDay -> "${slot.start.weekdayName()} at ${slot.start.clockTime()}"
                else -> "Starts at ${slot.start.clockTime()}"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (live) AccentDeep else InkSoft,
        )
    }
}

@Composable
private fun EventCard(name: String, statusLine: String, live: Boolean, bannerUrl: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PaperLight),
    ) {
        if (bannerUrl != null) BannerArt(bannerUrl)
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(3.dp))
                Text(statusLine, style = MaterialTheme.typography.bodyLarge, color = if (live) AccentDeep else InkSoft)
            }
            if (live) {
                Text(
                    text = "LIVE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Accent)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Banner art, downloaded once and cached on disk; a failure just leaves the row text-only. */
@Composable
private fun BannerArt(url: String) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) { BannerImageLoader.load(context, url) }
    }
    val image = bitmap ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(image.width.toFloat() / image.height.toFloat()),
    )
}
