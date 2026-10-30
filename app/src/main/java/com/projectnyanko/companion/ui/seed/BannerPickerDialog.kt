package com.projectnyanko.companion.ui.seed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectnyanko.companion.domain.seed.GameRegion
import com.projectnyanko.companion.ui.theme.Accent
import com.projectnyanko.companion.ui.theme.AccentDeep
import com.projectnyanko.companion.ui.theme.Good
import com.projectnyanko.companion.ui.theme.Ink
import com.projectnyanko.companion.ui.theme.InkSoft
import com.projectnyanko.companion.ui.theme.NyankoPillButton
import com.projectnyanko.companion.ui.theme.NyankoRow
import com.projectnyanko.companion.ui.theme.NyankoTextField
import com.projectnyanko.companion.ui.theme.NyankoToggleChip

private const val MAX_SEARCH_RESULTS = 80

@Composable
fun BannerPickerDialog(
    events: List<EventOption>,
    selectedId: String?,
    region: GameRegion,
    onRegionChange: (GameRegion) -> Unit,
    onSelect: (EventOption) -> Unit,
    onDismiss: () -> Unit,
) {
    NyankoDialogFrame(onDismiss = onDismiss) {
        DialogHeader(
            title = "Banner",
            subtitle = "The region and pool have to match the ones you rolled on.",
        )
        Spacer(Modifier.height(10.dp))
        BannerListContent(
            events = events,
            selectedId = selectedId,
            region = region,
            onRegionChange = onRegionChange,
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(10.dp))
        NyankoPillButton(
            text = "Cancel",
            onClick = onDismiss,
            filled = false,
            icon = null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Searchable banner list with the same two filters the web tracker exposes: which client the pool
 * belongs to, and whether the event is running, upcoming or finished.
 */
@Composable
fun BannerListContent(
    events: List<EventOption>,
    selectedId: String?,
    region: GameRegion,
    onRegionChange: (GameRegion) -> Unit,
    onSelect: (EventOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var showUpcoming by remember { mutableStateOf(true) }
    var showPast by remember { mutableStateOf(false) }

    val trimmed = query.trim()
    val matches = remember(trimmed, events, showUpcoming, showPast) {
        val needle = trimmed.lowercase()
        events.filter { event ->
            // The web tracker has no "now" section: events that are running sit at the top of
            // Upcoming, because a live banner is still a banner you can roll on.
            val visible = if (event.status == EventStatus.PAST) showPast else showUpcoming
            visible && (needle.isEmpty() || event.searchKey.contains(needle))
        }.take(if (needle.isEmpty()) Int.MAX_VALUE else MAX_SEARCH_RESULTS)
    }

    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NyankoToggleChip("BCEN", region == GameRegion.EN) { onRegionChange(GameRegion.EN) }
            NyankoToggleChip("BCJP", region == GameRegion.JP) { onRegionChange(GameRegion.JP) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NyankoToggleChip("Upcoming", showUpcoming) { showUpcoming = !showUpcoming }
            NyankoToggleChip("Past", showPast) { showPast = !showPast }
        }
        Spacer(Modifier.height(8.dp))
        NyankoTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search name, date or event id",
            leadingIcon = null,
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (matches.isEmpty()) {
                item {
                    Text(
                        text = if (trimmed.isEmpty()) {
                            "Nothing matches those filters."
                        } else {
                            "No banner matches \"$trimmed\"."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSoft,
                    )
                }
            } else {
                val upcoming = matches.filter { it.status != EventStatus.PAST }
                val past = matches.filter { it.status == EventStatus.PAST }
                listOf("Upcoming" to upcoming, "Past" to past).forEach { (label, group) ->
                    if (group.isEmpty()) return@forEach
                    item(key = "header-$label") {
                        Text(
                            text = "$label  ·  ${group.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkSoft,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                    }
                    items(group, key = { it.eventId }) { event ->
                        EventRow(event, event.eventId == selectedId) { onSelect(event) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: EventOption, selected: Boolean, onClick: () -> Unit) {
    NyankoRow(highlight = selected, onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = event.name,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${event.startDate} → ${event.endDate}",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft,
                )
                if (event.status == EventStatus.ONGOING) {
                    Spacer(Modifier.width(4.dp))
                    MicroChip("live", background = Good.copy(alpha = 0.25f))
                }
                if (event.guaranteedRolls > 0) {
                    Spacer(Modifier.width(4.dp))
                    MicroChip("${event.guaranteedRolls}-roll", background = AccentDeep.copy(alpha = 0.25f))
                }
            }
        }
        if (selected) {
            MicroChip("picked", background = Accent)
        }
    }
}

internal fun EventStatus.label(): String = when (this) {
    EventStatus.ONGOING -> "Running now"
    EventStatus.UPCOMING -> "Upcoming"
    EventStatus.PAST -> "Finished"
}

/** Header strip that shows the currently selected banner with a change action. */
@Composable
fun BannerSelectorRow(event: EventOption?, onChange: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Banner", style = MaterialTheme.typography.labelSmall, color = InkSoft)
            Text(
                text = event?.name ?: "None selected",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NyankoPillButton(
            text = "Change",
            onClick = onChange,
            filled = false,
            icon = null,
            height = 40,
        )
    }
    if (event != null) {
        Text(
            text = "${event.startDate} → ${event.endDate}  ·  ${event.eventId}",
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft,
        )
    }
}
