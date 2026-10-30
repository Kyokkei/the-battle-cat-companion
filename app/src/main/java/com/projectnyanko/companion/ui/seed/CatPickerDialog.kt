package com.projectnyanko.companion.ui.seed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.projectnyanko.companion.domain.seed.CatRarity
import com.projectnyanko.companion.domain.seed.GachaPool
import com.projectnyanko.companion.ui.theme.Ink
import com.projectnyanko.companion.ui.theme.InkSoft
import com.projectnyanko.companion.ui.theme.NyankoPillButton
import com.projectnyanko.companion.ui.theme.NyankoRow
import com.projectnyanko.companion.ui.theme.NyankoTextField

/**
 * Picks one cat from a banner's capsule pool.
 *
 * A unit that appears twice inside the same rarity is listed once; the seeker tries every slot it
 * could have come from, so the player never has to guess which duplicate they hit.
 */
@Composable
fun CatPickerDialog(
    pool: GachaPool,
    title: String,
    onPick: (SeekRollInput) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(pool, title) { mutableStateOf("") }
    val sections = remember(query, pool) {
        CatRarity.entries.mapNotNull { rarity ->
            val slots = pool.slots(rarity)
            if (slots.isEmpty()) return@mapNotNull null
            val cats = slots
                .distinctBy { it.id }
                .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                .sortedBy { it.name }
            if (cats.isEmpty()) null else rarity to cats
        }
    }

    NyankoDialogFrame(onDismiss = onDismiss) {
        DialogHeader(title = title, subtitle = "Pick exactly what the game showed you.")
        Spacer(Modifier.height(10.dp))
        NyankoTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search cat name",
            leadingIcon = null,
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (sections.isEmpty()) {
                item {
                    Text(
                        "No cat in this banner matches \"${query.trim()}\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSoft,
                    )
                }
            }
            sections.forEach { (rarity, cats) ->
                item(key = "h-${rarity.name}") {
                    Text(
                        text = "${rarity.displayName}  ·  ${cats.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSoft,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
                items(cats, key = { "${rarity.name}-${it.id}" }) { slot ->
                    NyankoRow(onClick = { onPick(SeekRollInput(slot.id, slot.name, rarity)) }) {
                        RarityDot(rarity)
                        Text(
                            text = slot.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
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

/** Compact summary of a pool so the seek form can show what it will search against. */
@Composable
fun PoolSummary(pool: GachaPool, modifier: Modifier = Modifier) {
    val parts = CatRarity.entries.mapNotNull { rarity ->
        val size = pool.slots(rarity).size
        if (size == 0) null else "${rarity.displayName.take(1)} $size"
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = parts.joinToString("  ·  "),
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft,
        )
    }
    Column {
        Text(
            text = "Rates ${pool.rareWeight / 100.0}% / ${pool.superRareWeight / 100.0}% / " +
                "${pool.uberRareWeight / 100.0}% / ${(10_000 - pool.rareWeight - pool.superRareWeight - pool.uberRareWeight) / 100.0}%",
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft,
        )
    }
}
