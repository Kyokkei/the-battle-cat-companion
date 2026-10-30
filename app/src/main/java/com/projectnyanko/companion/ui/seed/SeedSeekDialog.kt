package com.projectnyanko.companion.ui.seed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnyanko.companion.domain.seed.GameRegion
import com.projectnyanko.companion.domain.seed.TrackMarker
import com.projectnyanko.companion.ui.theme.Accent
import com.projectnyanko.companion.ui.theme.AccentDeep
import com.projectnyanko.companion.ui.theme.Danger
import com.projectnyanko.companion.ui.theme.Good
import com.projectnyanko.companion.ui.theme.Ink
import com.projectnyanko.companion.ui.theme.InkSoft
import com.projectnyanko.companion.ui.theme.NyankoPillButton
import com.projectnyanko.companion.ui.theme.NyankoTextField
import com.projectnyanko.companion.ui.theme.Paper
import com.projectnyanko.companion.ui.theme.PaperDeep
import java.util.Locale

/**
 * The whole "I do not know my seed yet" flow: pick the banner, type the cats, search locally.
 *
 * Nothing leaves the phone. The search is the same exhaustive 2^32 sweep the web tracker runs,
 * just on the device's own cores instead of a shared queue.
 */
@Composable
fun SeedSeekDialog(
    state: SeekUiState,
    events: List<EventOption>,
    region: GameRegion,
    onRegionChange: (GameRegion) -> Unit,
    onChooseEvent: (EventOption) -> Unit,
    onSetRoll: (Int, SeekRollInput?) -> Unit,
    onClear: () -> Unit,
    onStart: () -> Unit,
    onCancelRun: () -> Unit,
    onTrack: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showBannerPicker by remember { mutableStateOf(false) }
    var catPickerIndex by remember { mutableStateOf<Int?>(null) }

    NyankoDialogFrame(onDismiss = onDismiss, dismissable = !state.running) {
        DialogHeader(
            title = if (state.result != null) "Seed found" else "Find my seed",
            subtitle = if (state.result != null) {
                null
            } else {
                "Type the cats in the order you rolled them. Everything runs on this phone."
            },
        )
        Spacer(Modifier.height(12.dp))

        when {
            state.result != null -> SeekResultBody(
                result = state.result,
                eventName = state.event?.name.orEmpty(),
                onTrack = onTrack,
                onDismiss = onDismiss,
                modifier = Modifier.weight(1f),
            )

            state.running -> SeekRunningBody(
                state = state,
                onCancel = onCancelRun,
                modifier = Modifier.weight(1f),
            )

            else -> SeekFormBody(
                state = state,
                onChangeBanner = { showBannerPicker = true },
                onSetRoll = { index -> catPickerIndex = index },
                onClearRoll = { index -> onSetRoll(index, null) },
                onClear = onClear,
                onStart = onStart,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showBannerPicker) {
        BannerPickerDialog(
            events = events,
            selectedId = state.event?.eventId,
            region = region,
            onRegionChange = onRegionChange,
            onSelect = { event ->
                showBannerPicker = false
                onChooseEvent(event)
            },
            onDismiss = { showBannerPicker = false },
        )
    }

    val pool = state.pool
    val index = catPickerIndex
    if (pool != null && index != null) {
        CatPickerDialog(
            pool = pool,
            title = "Roll ${index + 1}",
            onPick = { roll ->
                onSetRoll(index, roll)
                // Rolling is sequential in the game, so jump straight to the next empty slot
                // instead of making the player tap it.
                catPickerIndex = nextEmptySlot(state.rolls, index)
            },
            onDismiss = { catPickerIndex = null },
        )
    }
}

private fun nextEmptySlot(rolls: List<SeekRollInput?>, picked: Int): Int? {
    val next = rolls.indices.firstOrNull { it > picked && rolls[it] == null } ?: return null
    return next
}

@Composable
private fun SeekFormBody(
    state: SeekUiState,
    onChangeBanner: () -> Unit,
    onSetRoll: (Int) -> Unit,
    onClearRoll: (Int) -> Unit,
    onClear: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        BannerSelectorRow(event = state.event, onChange = onChangeBanner)
        Spacer(Modifier.height(8.dp))
        state.pool?.let { pool ->
            PoolSummary(pool)
        }
        if (state.loadingPool) {
            Text("Loading the banner pool…", style = MaterialTheme.typography.bodySmall, color = InkSoft)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Your rolls", style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(
                text = "${state.filledRolls.size} / $MAX_SEEK_ROLLS",
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft,
            )
        }
        Text(
            text = "10 rolls is best · on an 11-roll guarantee, skip the last uber",
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(state.rolls) { index, roll ->
                RollSlotRow(
                    index = index,
                    roll = roll,
                    enabled = state.pool != null,
                    onPick = { onSetRoll(index) },
                    onClear = { onClearRoll(index) },
                )
            }
        }
        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Danger,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NyankoPillButton(
                text = "Clear",
                onClick = onClear,
                filled = false,
                icon = null,
                modifier = Modifier.weight(1f),
            )
            NyankoPillButton(
                text = "Find seed",
                onClick = onStart,
                enabled = state.canSeek,
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

@Composable
private fun RollSlotRow(
    index: Int,
    roll: SeekRollInput?,
    enabled: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (roll != null) PaperDeep else Paper)
            .border(2.dp, Ink.copy(alpha = if (enabled) 0.85f else 0.3f), shape)
            .clickable(enabled = enabled, onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelLarge,
            color = InkSoft,
            maxLines = 1,
            modifier = Modifier.width(22.dp),
        )
        if (roll == null) {
            Text(
                text = if (enabled) "Tap to pick a cat" else "Pick a banner first",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSoft,
                modifier = Modifier.weight(1f),
            )
        } else {
            RarityDot(roll.rarity)
            Text(
                text = roll.name,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text("×", color = InkSoft, fontSize = 18.sp) }
        }
    }
}

@Composable
private fun SeekRunningBody(state: SeekUiState, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val progress = state.progress
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Scanning seeds",
            style = MaterialTheme.typography.headlineMedium,
            color = Ink,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = progress?.let { formatCount(it.scanned) } ?: "0",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = InkSoft,
        )
        Text(
            text = "of 4,294,967,296",
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft,
        )
        Spacer(Modifier.height(18.dp))
        LinearProgressIndicator(
            progress = { progress?.fraction ?: 0f },
            modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)),
            color = AccentDeep,
            trackColor = PaperDeep,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${((progress?.fraction ?: 0f) * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = Ink,
            )
            Text(
                text = "${((progress?.elapsedMillis ?: 0L) / 1000.0).formatSeconds()}s",
                style = MaterialTheme.typography.labelLarge,
                color = InkSoft,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "The first pass checks every seed; if your first roll was itself a duplicate " +
                "reroll it retries two more times. Keep the app open.",
            style = MaterialTheme.typography.bodySmall,
            color = InkSoft,
        )
        Spacer(Modifier.height(16.dp))
        NyankoPillButton(
            text = "Cancel",
            onClick = onCancel,
            filled = false,
            icon = null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SeekResultBody(
    result: SeekResultUi,
    eventName: String,
    onTrack: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var name by remember(result.startingSeed) { mutableStateOf("Seed ${result.startingSeed}") }

    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(PaperDeep)
                .border(2.dp, Ink, RoundedCornerShape(18.dp))
                .padding(16.dp),
        ) {
            Text("YOUR SEED", style = MaterialTheme.typography.labelSmall, color = InkSoft)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = result.startingSeed.toString(),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 30.sp,
                    ),
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { copySeed(context, result.startingSeed) }) {
                    Text("Copy", color = AccentDeep, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                text = "Paste this into the web tracker, or keep tracking here.",
                style = MaterialTheme.typography.labelSmall,
                color = InkSoft,
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Matched ${result.rolls.size} rolls on $eventName in " +
                "${(result.elapsedMillis / 1000.0).formatSeconds()}s.",
            style = MaterialTheme.typography.bodySmall,
            color = Good,
        )
        if (result.ambiguous) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "More than one seed reproduces these rolls, so this might not be yours. " +
                    "Roll once more and search again with the extra cat to pin it down.",
                style = MaterialTheme.typography.bodySmall,
                color = Danger,
            )
        }

        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(result.rolls) { index, roll ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSoft,
                        modifier = Modifier.width(16.dp),
                    )
                    RarityDot(roll.rarity)
                    Text(
                        text = roll.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        NyankoTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = "Profile name",
            label = "Save as",
            leadingIcon = null,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NyankoPillButton(
                text = "Close",
                onClick = onDismiss,
                filled = false,
                icon = null,
                modifier = Modifier.weight(1f),
            )
            NyankoPillButton(
                text = "Track it",
                onClick = { onTrack(name) },
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

private fun copySeed(context: Context, seed: Long) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Battle Cats seed", seed.toString()))
    Toast.makeText(context, "Seed $seed copied", Toast.LENGTH_SHORT).show()
}

private fun formatCount(value: Long): String = String.format(Locale.ROOT, "%,d", value)

private fun Double.formatSeconds(): String = String.format(Locale.ROOT, "%.1f", this)

/** Small helper so the seek form and the tracker agree on how a position reads. */
internal fun TrackMarker.displayLabel(): String = toString()

@Composable
internal fun MarkerBadge(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Accent)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = Ink,
        )
    }
}
