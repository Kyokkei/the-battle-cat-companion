package com.projectnyanko.companion.ui.event

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.projectnyanko.companion.data.event.BannerImageLoader
import com.projectnyanko.companion.data.event.EventScheduleRepository
import com.projectnyanko.companion.domain.event.EventKind
import com.projectnyanko.companion.domain.event.EventStatus
import com.projectnyanko.companion.domain.event.RecurringEvent
import com.projectnyanko.companion.domain.event.ScheduledEvent
import com.projectnyanko.companion.ui.theme.Accent
import com.projectnyanko.companion.ui.theme.AccentDeep
import com.projectnyanko.companion.ui.theme.Ink
import com.projectnyanko.companion.ui.theme.InkSoft
import com.projectnyanko.companion.ui.theme.Paper
import com.projectnyanko.companion.ui.theme.PaperLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Section(val title: String) {
    GACHA("Gacha"),
    SPECIAL("Special Events"),
    RECURRING("Recurring"),
}

/**
 * The Event tab: a bento of what is happening, then one list per tile.
 *
 * Everything comes from the wiki schedule snapshot, which cannot refresh itself (that wiki only
 * answers a browser), so the header says when it was taken. The gacha banner art is bundled next to
 * it — the app asks for no network permission at all.
 */
@Composable
fun EventsScreen(repository: EventScheduleRepository) {
    val schedule = remember { repository.load() }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) }
    var open by remember { mutableStateOf<Section?>(null) }

    val gacha = schedule?.ordered(EventKind.GACHA, today).orEmpty()
    val special = schedule?.ordered(EventKind.SPECIAL, today).orEmpty()
    val recurring = schedule?.recurring.orEmpty()

    when (open) {
        null -> Bento(
            today = today,
            gacha = gacha,
            special = special,
            recurring = recurring,
            fetched = schedule?.fetched.orEmpty(),
            onOpen = { open = it },
        )
        Section.GACHA -> GachaList(gacha, repository, today) { open = null }
        Section.SPECIAL -> SpecialList(special, today) { open = null }
        Section.RECURRING -> RecurringList(recurring) { open = null }
    }
}

@Composable
private fun Bento(
    today: String,
    gacha: List<ScheduledEvent>,
    special: List<ScheduledEvent>,
    recurring: List<RecurringEvent>,
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
            style = MaterialTheme.typography.bodySmall,
            color = InkSoft,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BentoTile(
                title = "Gacha",
                big = "$liveGacha live",
                caption = soonestGacha?.let { "next ends in ${it.daysLeft(today) ?: 0} d" } ?: "nothing live",
                modifier = Modifier.weight(1f),
            ) { onOpen(Section.GACHA) }
            BentoTile(
                title = "Special",
                big = "$liveSpecial live",
                caption = special.firstOrNull { it.status(today) == EventStatus.LIVE }
                    ?.let { "ends in ${it.daysLeft(today) ?: 0} d" } ?: "none live",
                modifier = Modifier.weight(1f),
            ) { onOpen(Section.SPECIAL) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BentoTile(
                title = "Recurring",
                big = "${recurring.size} stages",
                caption = "weekly & monthly",
                modifier = Modifier.weight(1f),
            ) { onOpen(Section.RECURRING) }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun BentoTile(
    title: String,
    big: String,
    caption: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(PaperLight)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AccentDeep)
        Spacer(Modifier.height(6.dp))
        Text(big, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = InkSoft)
    }
}

@Composable
private fun ListHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "‹",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = AccentDeep,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(4.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = InkSoft)
            }
        }
    }
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
                    EventStatus.LIVE -> "Ends in ${event.daysLeft(today) ?: 0} d"
                    else -> "Starts in ${event.daysUntilStart(today) ?: 0} d"
                },
                live = event.status(today) == EventStatus.LIVE,
                bannerUrl = repository.bannerUrl(event.name),
            )
        }
    }
}

@Composable
private fun SpecialList(events: List<ScheduledEvent>, today: String, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(Paper), contentPadding = PaddingValues(16.dp)) {
        item {
            ListHeader("Special Events", "From the wiki schedule snapshot", onBack)
            Spacer(Modifier.height(12.dp))
        }
        items(events) { event ->
            EventCard(
                name = event.name,
                statusLine = when (event.status(today)) {
                    EventStatus.LIVE -> "Ends in ${event.daysLeft(today) ?: 0} d"
                    else -> "Starts in ${event.daysUntilStart(today) ?: 0} d"
                },
                live = event.status(today) == EventStatus.LIVE,
                bannerUrl = null,
            )
        }
    }
}

@Composable
private fun RecurringList(events: List<RecurringEvent>, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(Paper), contentPadding = PaddingValues(16.dp)) {
        item {
            ListHeader("Recurring", "Fixed cycle · window set by the daily reset", onBack)
            Spacer(Modifier.height(12.dp))
        }
        items(events) { event ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PaperLight)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(event.name, style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
                Text(
                    "${event.cadenceLabel} · ${event.window}",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                )
            }
        }
    }
}

@Composable
private fun EventCard(
    name: String,
    statusLine: String,
    live: Boolean,
    bannerUrl: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PaperLight),
    ) {
        if (bannerUrl != null) BannerArt(bannerUrl)
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(
                    statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (live) AccentDeep else InkSoft,
                )
            }
            if (live) Pill("LIVE", Accent)
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

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Ink,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
