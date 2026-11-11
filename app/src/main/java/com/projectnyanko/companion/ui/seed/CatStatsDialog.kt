package com.projectnyanko.companion.ui.seed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnyanko.companion.data.catalog.UnitFormStats
import com.projectnyanko.companion.data.catalog.UnitStats
import com.projectnyanko.companion.domain.cat.InGameCooldown
import com.projectnyanko.companion.ui.theme.AccentDeep
import com.projectnyanko.companion.ui.theme.Ink
import com.projectnyanko.companion.ui.theme.InkSoft
import com.projectnyanko.companion.ui.theme.NyankoPillButton
import com.projectnyanko.companion.ui.theme.PaperDeep
import com.projectnyanko.companion.ui.theme.PaperLight
import java.util.Locale

/** The web tracker's cat page: every evolution stage with its level-30 numbers. */
@Composable
fun CatStatsDialog(
    state: StatsUiState,
    spirits: List<UnitStats> = emptyList(),
    targetFormIndex: Int? = null,
    onDismiss: () -> Unit,
) {
    NyankoDialogFrame(onDismiss = onDismiss) {
        val stats = state.stats
        val headerTitle = if (targetFormIndex != null && stats != null && targetFormIndex in stats.forms.indices) {
            stats.forms[targetFormIndex].name
        } else {
            stats?.forms?.firstOrNull()?.name ?: "Cat stats"
        }
        val headerSubtitle = stats?.let { unit ->
            if (targetFormIndex != null && targetFormIndex in unit.forms.indices) {
                buildString {
                    unit.rarity?.let { append(it.displayName).append("  ·  ") }
                    append("Lv ${unit.level}")
                    append("  ·  ").append(formLabel(targetFormIndex))
                }
            } else {
                buildString {
                    unit.rarity?.let { append(it.displayName).append("  ·  ") }
                    append("Lv ${unit.level}")
                    append("  ·  ${unit.forms.size} form").append(if (unit.forms.size == 1) "" else "s")
                }
            }
        } ?: "Reading the bundled stat table…"

        DialogHeader(
            title = headerTitle,
            subtitle = headerSubtitle,
        )
        Spacer(Modifier.height(12.dp))
        when {
            state.loading -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = AccentDeep)
            }

            stats == null -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No stats bundled for this unit.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSoft,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val formsToRender = if (targetFormIndex != null && targetFormIndex in stats.forms.indices) {
                    listOf(targetFormIndex to stats.forms[targetFormIndex])
                } else {
                    stats.forms.mapIndexed { idx, form -> idx to form }
                }

                items(formsToRender, key = { (idx, form) -> "${form.name}_$idx" }) { (index, form) ->
                    FormCard(unitId = stats.id, formIndex = index, form = form)
                }
                if (spirits.isNotEmpty()) {
                    item(key = "spirits_divider") {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(2.dp)
                                    .background(Ink.copy(alpha = 0.25f))
                            )
                            Text(
                                text = "✨ CONJURED SPIRIT",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = AccentDeep,
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(2.dp)
                                    .background(Ink.copy(alpha = 0.25f))
                            )
                        }
                    }
                    items(spirits, key = { "spirit_${it.id}" }) { spiritUnit ->
                        spiritUnit.forms.forEachIndexed { fIdx, sForm ->
                            val summonerName = stats.forms.firstOrNull()?.name ?: "Cat"
                            val displaySpiritName = if (sForm.name.matches(Regex(".*\\d+[-_]\\d+.*"))) "Spirit of $summonerName" else sForm.name
                            FormCard(
                                unitId = spiritUnit.id,
                                formIndex = fIdx,
                                form = sForm.copy(name = displaySpiritName),
                                customLabel = "Conjured by $summonerName",
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        NyankoPillButton(
            text = "Close",
            onClick = onDismiss,
            filled = false,
            icon = null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FormCard(unitId: Int, formIndex: Int, form: UnitFormStats, customLabel: String? = null) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PaperDeep)
            .border(2.dp, Ink, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UnitSprite(unitId = unitId, formIndex = formIndex, size = 62.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = form.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = customLabel ?: formLabel(formIndex),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (customLabel != null) AccentDeep else InkSoft,
                    fontWeight = if (customLabel != null) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        StatPair("Health", format(form.health), "Knockbacks", form.knockbacks.toString())
        StatPair("Attack", format(form.attack), "Range", form.range.toString())
        StatPair("DPS", format(form.dps), "Speed", "${form.speedPerSecond}/s")
        StatPair("Cost", format(form.cost), "Recharge", InGameCooldown.format(form.rechargeSeconds))
        StatPair(
            "Attack cycle",
            seconds(form.attackCycleSeconds),
            "Targets",
            if (form.areaAttack) "Area" else "Single",
        )
        if (form.targets.isNotEmpty() || form.immune.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            form.targets.takeIf { it.isNotEmpty() }?.let { traits ->
                TraitRow("Strong vs", traits)
            }
            form.immune.takeIf { it.isNotEmpty() }?.let { traits ->
                TraitRow("Immune to", traits)
            }
        }
        if (form.effects.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            TraitRow("Abilities", form.effects)
        }
    }
}

@Composable
internal fun StatPair(labelLeft: String, valueLeft: String, labelRight: String, valueRight: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        StatCell(labelLeft, valueLeft, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        StatCell(labelRight, valueRight, Modifier.weight(1f))
    }
}

@Composable
internal fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PaperLight)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Black,
            color = Ink,
        )
    }
}

@Composable
internal fun TraitRow(label: String, values: List<String>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft,
            modifier = Modifier.width(78.dp),
        )
        Text(
            text = values.joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            color = Ink,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun format(value: Int): String = String.format(Locale.ROOT, "%,d", value)

private fun seconds(value: Double): String =
    if (value <= 0.0) "—" else String.format(Locale.ROOT, "%.2fs", value)
