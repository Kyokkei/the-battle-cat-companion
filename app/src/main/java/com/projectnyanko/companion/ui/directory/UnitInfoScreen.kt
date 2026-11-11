package com.projectnyanko.companion.ui.directory

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnyanko.companion.R
import com.projectnyanko.companion.data.catalog.UnitFormStats
import com.projectnyanko.companion.data.catalog.UnitStats
import com.projectnyanko.companion.data.catalog.UnitStatsRepository
import com.projectnyanko.companion.data.stage.EnemyStatsRepository
import com.projectnyanko.companion.domain.seed.CatRarity
import com.projectnyanko.companion.domain.seed.GameRegion
import com.projectnyanko.companion.domain.stage.EnemyStats
import com.projectnyanko.companion.ui.seed.CatStatsDialog
import com.projectnyanko.companion.ui.seed.DialogHeader
import com.projectnyanko.companion.ui.seed.NyankoDialogFrame
import com.projectnyanko.companion.ui.seed.StatPair
import com.projectnyanko.companion.ui.seed.StatsUiState
import com.projectnyanko.companion.ui.seed.TraitRow
import com.projectnyanko.companion.ui.seed.UnitSprite
import com.projectnyanko.companion.ui.theme.Accent
import com.projectnyanko.companion.ui.theme.AccentDeep
import com.projectnyanko.companion.ui.theme.Ink
import com.projectnyanko.companion.ui.theme.InkSoft
import com.projectnyanko.companion.ui.theme.NyankoCaption
import com.projectnyanko.companion.ui.theme.NyankoPanel
import com.projectnyanko.companion.ui.theme.NyankoPillButton
import com.projectnyanko.companion.ui.theme.NyankoRow
import com.projectnyanko.companion.ui.theme.NyankoTextField
import com.projectnyanko.companion.ui.theme.NyankoToggleChip
import com.projectnyanko.companion.ui.theme.Paper
import com.projectnyanko.companion.ui.theme.PaperDeep
import com.projectnyanko.companion.ui.theme.PaperLight

private enum class DirectorySubView {
    HUB,
    CATS,
    ENEMIES,
}

private enum class DirectoryRarity(val label: String) {
    NORMAL("Normal"),
    SPECIAL("Special"),
    RARE("Rare"),
    SUPER_RARE("Super Rare"),
    UBER_RARE("Uber Rare"),
    LEGEND_RARE("Legend Rare");

    companion object {
        fun of(unit: UnitStats): DirectoryRarity = when (unit.rarity) {
            CatRarity.RARE -> RARE
            CatRarity.SUPER_RARE -> SUPER_RARE
            CatRarity.UBER_RARE -> UBER_RARE
            CatRarity.LEGEND_RARE -> LEGEND_RARE
            null -> if (unit.id < 9 || unit.id == 643) NORMAL else SPECIAL
        }
    }
}

private enum class DirectoryFormStage(val index: Int, val label: String) {
    BASE(0, "Base"),
    EVOLVED(1, "Evolved"),
    TRUE_FORM(2, "True Form"),
    ULTRA_FORM(3, "Ultra Form");
}

private enum class FilterMatchMode(val label: String) {
    ANY("Any (OR)"),
    ALL("All (AND)");
}

private data class CatDirectoryItem(
    val unit: UnitStats,
    val formIndex: Int,
    val form: UnitFormStats,
)

/** Mapping of summoner cat ID to its summoned/conjured spirit unit IDs */
private val summonerToSpirits = mapOf(
    728 to listOf(729),
    731 to listOf(732),
    733 to listOf(734),
    738 to listOf(739),
    754 to listOf(755),
    760 to listOf(761),
    763 to listOf(764),
    769 to listOf(770),
    774 to listOf(775),
    781 to listOf(782),
    799 to listOf(800),
    801 to listOf(802),
    811 to listOf(812),
    815 to listOf(816),
    817 to listOf(818),
    820 to listOf(821),
    837 to listOf(838, 839),
    854 to listOf(855),
    859 to listOf(860),
    874 to listOf(875, 876),
    886 to listOf(887),
)

private val allSpiritUnitIds = summonerToSpirits.values.flatten().toSet()

private val catTraits = listOf(
    "Red", "Floating", "Black", "Metal", "Angel", "Alien", "Zombie", "Aku", "Relic", "Sage", "Traitless"
)

private val catAbilities = listOf(
    "Massive damage", "Insane damage", "Tough vs", "Strong vs",
    "Freeze", "Slow", "Knockback", "Weaken",
    "Wave", "Surge", "Critical", "Zombie Killer",
    "Barrier Breaker", "Shield Pierce", "Dodge", "Strengthen",
    "Curse", "Wave Immune", "Surge Immune"
)

private fun matchesUnitAbility(form: UnitFormStats, ability: String): Boolean {
    val effects = form.effects
    val immune = form.immune
    return when (ability) {
        "Massive damage" -> effects.any { it.contains("Massive", ignoreCase = true) }
        "Insane damage" -> effects.any { it.contains("Insane", ignoreCase = true) }
        "Strong vs" -> effects.any { it.contains("Strong", ignoreCase = true) }
        "Tough vs" -> effects.any { it.contains("Resistant", ignoreCase = true) || it.contains("Insanely", ignoreCase = true) }
        "Freeze" -> effects.any { it.contains("Freeze", ignoreCase = true) }
        "Slow" -> effects.any { it.contains("Slow", ignoreCase = true) }
        "Knockback" -> effects.any { it.contains("Knockback", ignoreCase = true) }
        "Weaken" -> effects.any { it.contains("Weaken", ignoreCase = true) }
        "Wave" -> effects.any { it.contains("Wave", ignoreCase = true) && !it.contains("Wave Shield", ignoreCase = true) }
        "Surge" -> effects.any { it.contains("Surge", ignoreCase = true) && !it.contains("Counter-surge", ignoreCase = true) }
        "Critical" -> effects.any { it.contains("Critical", ignoreCase = true) }
        "Zombie Killer" -> effects.any { it.contains("Zombie", ignoreCase = true) }
        "Barrier Breaker" -> effects.any { it.contains("barrier", ignoreCase = true) }
        "Shield Pierce" -> effects.any { it.contains("pierce", ignoreCase = true) || it.contains("shield", ignoreCase = true) }
        "Dodge" -> effects.any { it.contains("Dodge", ignoreCase = true) }
        "Strengthen" -> effects.any { it.contains("Attack +", ignoreCase = true) }
        "Curse" -> effects.any { it.contains("Curse", ignoreCase = true) }
        "Wave Immune" -> immune.any { it.contains("Wave", ignoreCase = true) } || effects.any { it.contains("Wave Shield", ignoreCase = true) }
        "Surge Immune" -> immune.any { it.contains("Surge", ignoreCase = true) }
        "Curse Immune" -> immune.any { it.contains("Curse", ignoreCase = true) }
        "Toxic Immune" -> immune.any { it.contains("Toxic", ignoreCase = true) }
        else -> effects.any { it.contains(ability, ignoreCase = true) } || immune.any { it.contains(ability, ignoreCase = true) }
    }
}

private val enemyTraits = listOf(
    "Traitless", "Red", "Floating", "Black", "Metal", "Angel", "Alien", "Zombie", "Aku", "Relic", "Sage"
)

private val enemyAttackTypes = listOf("Area Atk", "Single Atk")

private val enemyAbilities = listOf(
    "Wave", "Surge", "Freeze", "Slow", "Knockback", "Weaken",
    "Warp", "Curse", "Critical", "Burrow", "Revive", "Barrier", "Shield"
)

@Composable
fun UnitInfoScreen(
    unitStatsRepository: UnitStatsRepository,
    enemyStatsRepository: EnemyStatsRepository,
) {
    var subView by remember { mutableStateOf(DirectorySubView.HUB) }

    when (subView) {
        DirectorySubView.HUB -> DirectoryHubView(
            onOpenCats = { subView = DirectorySubView.CATS },
            onOpenEnemies = { subView = DirectorySubView.ENEMIES },
        )
        DirectorySubView.CATS -> {
            BackHandler { subView = DirectorySubView.HUB }
            CatDirectoryScreen(
                repository = unitStatsRepository,
                onBack = { subView = DirectorySubView.HUB },
            )
        }
        DirectorySubView.ENEMIES -> {
            BackHandler { subView = DirectorySubView.HUB }
            EnemyDirectoryScreen(
                repository = enemyStatsRepository,
                onBack = { subView = DirectorySubView.HUB },
            )
        }
    }
}

@Composable
private fun DirectoryHubView(
    onOpenCats: () -> Unit,
    onOpenEnemies: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(
                text = "Unit Info",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = Ink,
            )
            Spacer(Modifier.height(4.dp))
            NyankoCaption("Search stats, traits, abilities, and form progressions.")
        }

        Spacer(Modifier.height(4.dp))

        // Cat Directory Card
        NyankoPanel(
            modifier = Modifier.clickable { onOpenCats() },
            contentPadding = PaddingValues(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cat Guide",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AccentDeep,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "888 Cats Discovered",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Ink,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Stats, DPS curves, true/ultra forms, targets & talents",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                    )
                }
                Text(
                    text = "›",
                    style = MaterialTheme.typography.headlineLarge,
                    color = AccentDeep,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }

        // Enemy Bestiary Card
        NyankoPanel(
            modifier = Modifier.clickable { onOpenEnemies() },
            contentPadding = PaddingValues(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enemy Bestiary",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AccentDeep,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "800 Enemies Indexed",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = Ink,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Attack ranges, speeds, knockbacks, traits & hazards",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                    )
                }
                Text(
                    text = "›",
                    style = MaterialTheme.typography.headlineLarge,
                    color = AccentDeep,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun CatDirectoryScreen(
    repository: UnitStatsRepository,
    onBack: () -> Unit,
) {
    var region by remember { mutableStateOf(GameRegion.EN) }
    // Filter out spirit units from the primary guide list; they are displayed inside their summoner's card
    val allUnits = remember(region) {
        repository.all(region).values.toList()
            .filter { it.id !in allSpiritUnitIds }
            .sortedBy { it.id }
    }
    val allItems = remember(allUnits) {
        allUnits.flatMap { unit ->
            unit.forms.mapIndexed { index, form ->
                CatDirectoryItem(unit = unit, formIndex = index, form = form)
            }
        }
    }
    var query by remember { mutableStateOf("") }
    var selectedRarities by remember { mutableStateOf(emptySet<DirectoryRarity>()) }
    var selectedForms by remember { mutableStateOf(emptySet<DirectoryFormStage>()) }
    var selectedTraits by remember { mutableStateOf(emptySet<String>()) }
    var selectedAbilities by remember { mutableStateOf(emptySet<String>()) }
    var matchMode by remember { mutableStateOf(FilterMatchMode.ALL) }
    var selectedItem by remember { mutableStateOf<CatDirectoryItem?>(null) }
    var showFilterDialog by remember { mutableStateOf(false) }

    val filteredItems = remember(allItems, query, selectedRarities, selectedForms, selectedTraits, selectedAbilities, matchMode) {
        val q = query.trim().lowercase()
        allItems.filter { item ->
            val matchesQuery = q.isEmpty() ||
                item.unit.id.toString() == q ||
                item.form.name.lowercase().contains(q) ||
                item.unit.forms.firstOrNull()?.name?.lowercase()?.contains(q) == true

            val matchesRarity = selectedRarities.isEmpty() ||
                selectedRarities.contains(DirectoryRarity.of(item.unit))

            val matchesForm = selectedForms.isEmpty() ||
                selectedForms.any { it.index == item.formIndex }

            val hasTraits = selectedTraits.isNotEmpty()
            val hasAbilities = selectedAbilities.isNotEmpty()

            val matchesTraitsAndAbilities = if (!hasTraits && !hasAbilities) {
                true
            } else if (matchMode == FilterMatchMode.ALL) {
                val traitMatch = !hasTraits || selectedTraits.all { trait ->
                    item.form.targets.any { t -> t.contains(trait, ignoreCase = true) }
                }
                val abilityMatch = !hasAbilities || selectedAbilities.all { ability ->
                    matchesUnitAbility(item.form, ability)
                }
                traitMatch && abilityMatch
            } else {
                val traitMatch = hasTraits && selectedTraits.any { trait ->
                    item.form.targets.any { t -> t.contains(trait, ignoreCase = true) }
                }
                val abilityMatch = hasAbilities && selectedAbilities.any { ability ->
                    matchesUnitAbility(item.form, ability)
                }
                traitMatch || abilityMatch
            }

            matchesQuery && matchesRarity && matchesForm && matchesTraitsAndAbilities
        }
    }

    selectedItem?.let { item ->
        val allUnitsMap = remember(region) { repository.all(region) }
        val spiritIds = summonerToSpirits[item.unit.id].orEmpty()
        val spirits = spiritIds.mapNotNull { allUnitsMap[it] }
        CatStatsDialog(
            state = StatsUiState(loading = false, stats = item.unit),
            spirits = spirits,
            targetFormIndex = item.formIndex,
            onDismiss = { selectedItem = null },
        )
    }

    if (showFilterDialog) {
        CatFilterDialog(
            initialRarities = selectedRarities,
            initialForms = selectedForms,
            initialTraits = selectedTraits,
            initialAbilities = selectedAbilities,
            initialMatchMode = matchMode,
            allItems = allItems,
            onDismiss = { showFilterDialog = false },
            onApply = { r, f, t, a, m ->
                selectedRarities = r
                selectedForms = f
                selectedTraits = t
                selectedAbilities = a
                matchMode = m
            },
        )
    }

    val activeFilterCount = selectedRarities.size + selectedForms.size + selectedTraits.size + selectedAbilities.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = "Back",
                modifier = Modifier
                    .size(28.dp)
                    .clickable { onBack() },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Cat Guide (${filteredItems.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = Ink,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NyankoToggleChip("EN", region == GameRegion.EN) { region = GameRegion.EN }
                NyankoToggleChip("JP", region == GameRegion.JP) { region = GameRegion.JP }
            }
        }

        // Search Bar & Filter Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NyankoTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search name, id, or ability…",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilterButton(
                activeCount = activeFilterCount,
                onClick = { showFilterDialog = true },
            )
        }

        // Active Filter Chips Bar
        if (activeFilterCount > 0) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (selectedTraits.isNotEmpty() || selectedAbilities.isNotEmpty()) {
                    ActiveFilterTag(
                        label = if (matchMode == FilterMatchMode.ALL) "Match: ALL (AND)" else "Match: ANY (OR)",
                        onRemove = {
                            matchMode = if (matchMode == FilterMatchMode.ALL) FilterMatchMode.ANY else FilterMatchMode.ALL
                        }
                    )
                }
                selectedRarities.forEach { rarity ->
                    ActiveFilterTag(label = rarity.label, onRemove = { selectedRarities = selectedRarities - rarity })
                }
                selectedForms.forEach { stage ->
                    ActiveFilterTag(label = stage.label, onRemove = { selectedForms = selectedForms - stage })
                }
                selectedTraits.forEach { trait ->
                    ActiveFilterTag(label = trait, onRemove = { selectedTraits = selectedTraits - trait })
                }
                selectedAbilities.forEach { ability ->
                    ActiveFilterTag(label = ability, onRemove = { selectedAbilities = selectedAbilities - ability })
                }
                Text(
                    text = "Clear all",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AccentDeep,
                    modifier = Modifier
                        .clickable {
                            selectedRarities = emptySet()
                            selectedForms = emptySet()
                            selectedTraits = emptySet()
                            selectedAbilities = emptySet()
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Unit List - Flattened by form (Tank Cat, Wall Cat, Eraser Cat, etc.)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filteredItems, key = { "${it.unit.id}_${it.formIndex}" }) { item ->
                val rarity = DirectoryRarity.of(item.unit)
                val hasSpirits = summonerToSpirits.containsKey(item.unit.id)
                val stageLabel = when (item.formIndex) {
                    0 -> "Base"
                    1 -> "Evolved"
                    2 -> "True"
                    else -> "Ultra"
                }
                NyankoRow(onClick = { selectedItem = item }) {
                    UnitSprite(
                        unitId = item.unit.id,
                        formIndex = item.formIndex,
                        size = 46.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "#${item.unit.id.toString().padStart(3, '0')}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = AccentDeep,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = rarity.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = InkSoft,
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Ink.copy(alpha = 0.08f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = stageLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = InkSoft,
                                    fontSize = 10.sp,
                                )
                            }
                            if (hasSpirits) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "✨ Spirit",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentDeep,
                                )
                            }
                        }
                        Text(
                            text = item.form.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                        if (item.form.targets.isNotEmpty()) {
                            Text(
                                text = "vs " + item.form.targets.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentDeep,
                                maxLines = 1,
                            )
                        }
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = InkSoft,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatFilterDialog(
    initialRarities: Set<DirectoryRarity>,
    initialForms: Set<DirectoryFormStage>,
    initialTraits: Set<String>,
    initialAbilities: Set<String>,
    initialMatchMode: FilterMatchMode,
    allItems: List<CatDirectoryItem>,
    onDismiss: () -> Unit,
    onApply: (Set<DirectoryRarity>, Set<DirectoryFormStage>, Set<String>, Set<String>, FilterMatchMode) -> Unit,
) {
    var tempRarities by remember { mutableStateOf(initialRarities) }
    var tempForms by remember { mutableStateOf(initialForms) }
    var tempTraits by remember { mutableStateOf(initialTraits) }
    var tempAbilities by remember { mutableStateOf(initialAbilities) }
    var tempMatchMode by remember { mutableStateOf(initialMatchMode) }

    val previewCount = remember(tempRarities, tempForms, tempTraits, tempAbilities, tempMatchMode, allItems) {
        allItems.count { item ->
            val matchesRarity = tempRarities.isEmpty() || tempRarities.contains(DirectoryRarity.of(item.unit))
            val matchesForm = tempForms.isEmpty() || tempForms.any { it.index == item.formIndex }
            val hasTraits = tempTraits.isNotEmpty()
            val hasAbilities = tempAbilities.isNotEmpty()

            val matchesTraitsAndAbilities = if (!hasTraits && !hasAbilities) {
                true
            } else if (tempMatchMode == FilterMatchMode.ALL) {
                val traitMatch = !hasTraits || tempTraits.all { trait ->
                    item.form.targets.any { t -> t.contains(trait, ignoreCase = true) }
                }
                val abilityMatch = !hasAbilities || tempAbilities.all { ability ->
                    matchesUnitAbility(item.form, ability)
                }
                traitMatch && abilityMatch
            } else {
                val traitMatch = hasTraits && tempTraits.any { trait ->
                    item.form.targets.any { t -> t.contains(trait, ignoreCase = true) }
                }
                val abilityMatch = hasAbilities && tempAbilities.any { ability ->
                    matchesUnitAbility(item.form, ability)
                }
                traitMatch || abilityMatch
            }

            matchesRarity && matchesForm && matchesTraitsAndAbilities
        }
    }

    NyankoDialogFrame(onDismiss = onDismiss) {
        DialogHeader(
            title = "Filter Units",
            subtitle = "$previewCount forms match selected criteria",
        )
        Spacer(Modifier.height(10.dp))

        // Match Mode Switcher (ANY vs ALL)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(PaperDeep)
                .border(1.5.dp, Ink, RoundedCornerShape(14.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "MATCH LOGIC",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = InkSoft,
                )
                Text(
                    text = if (tempMatchMode == FilterMatchMode.ALL) "Must have ALL selected" else "Any 1 selected is enough",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AccentDeep,
                    fontSize = 11.sp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val anyActive = tempMatchMode == FilterMatchMode.ANY
                val allActive = tempMatchMode == FilterMatchMode.ALL

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (anyActive) Accent else PaperLight)
                        .border(if (anyActive) 2.dp else 1.dp, Ink, RoundedCornerShape(10.dp))
                        .clickable { tempMatchMode = FilterMatchMode.ANY },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Any (OR)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (anyActive) FontWeight.Black else FontWeight.Bold,
                        color = Ink,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (allActive) Accent else PaperLight)
                        .border(if (allActive) 2.dp else 1.dp, Ink, RoundedCornerShape(10.dp))
                        .clickable { tempMatchMode = FilterMatchMode.ALL },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "All (AND)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (allActive) FontWeight.Black else FontWeight.Bold,
                        color = Ink,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            FilterSectionHeader("FORM STAGE")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DirectoryFormStage.entries.forEach { stage ->
                    val selected = tempForms.contains(stage)
                    NyankoToggleChip(stage.label, selected) {
                        tempForms = if (selected) tempForms - stage else tempForms + stage
                    }
                }
            }

            FilterSectionHeader("RARITY")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DirectoryRarity.entries.forEach { r ->
                    val selected = tempRarities.contains(r)
                    NyankoToggleChip(r.label, selected) {
                        tempRarities = if (selected) tempRarities - r else tempRarities + r
                    }
                }
            }

            FilterSectionHeader("TARGET TRAITS")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                catTraits.forEach { trait ->
                    val selected = tempTraits.contains(trait)
                    NyankoToggleChip(trait, selected) {
                        tempTraits = if (selected) tempTraits - trait else tempTraits + trait
                    }
                }
            }

            FilterSectionHeader("ABILITIES & PASSIVES")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                catAbilities.forEach { ability ->
                    val selected = tempAbilities.contains(ability)
                    NyankoToggleChip(ability, selected) {
                        tempAbilities = if (selected) tempAbilities - ability else tempAbilities + ability
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NyankoPillButton(
                text = "Reset",
                onClick = {
                    tempRarities = emptySet()
                    tempForms = emptySet()
                    tempTraits = emptySet()
                    tempAbilities = emptySet()
                    tempMatchMode = FilterMatchMode.ALL
                },
                filled = false,
                icon = null,
                modifier = Modifier.weight(0.75f),
            )
            NyankoPillButton(
                text = "Apply ($previewCount)",
                onClick = {
                    onApply(tempRarities, tempForms, tempTraits, tempAbilities, tempMatchMode)
                    onDismiss()
                },
                filled = true,
                icon = null,
                modifier = Modifier.weight(1.25f),
            )
        }
    }
}

@Composable
private fun EnemyDirectoryScreen(
    repository: EnemyStatsRepository,
    onBack: () -> Unit,
) {
    val allEnemies = remember { repository.all().values.toList().sortedBy { it.id } }
    var query by remember { mutableStateOf("") }
    var selectedTraits by remember { mutableStateOf(emptySet<String>()) }
    var selectedAttackType by remember { mutableStateOf<String?>(null) }
    var selectedAbilities by remember { mutableStateOf(emptySet<String>()) }
    var selectedEnemy by remember { mutableStateOf<EnemyStats?>(null) }
    var showFilterDialog by remember { mutableStateOf(false) }

    val filteredEnemies = remember(allEnemies, query, selectedTraits, selectedAttackType, selectedAbilities) {
        val q = query.trim().lowercase()
        allEnemies.filter { enemy ->
            val matchesQuery = q.isEmpty() ||
                enemy.id.toString() == q ||
                enemy.name.lowercase().contains(q)

            val matchesTrait = selectedTraits.isEmpty() ||
                selectedTraits.any { trait ->
                    if (trait.equals("Traitless", ignoreCase = true)) {
                        enemy.traits.isEmpty() || enemy.traits.any { it.contains("Traitless", ignoreCase = true) }
                    } else {
                        enemy.traits.any { it.contains(trait, ignoreCase = true) }
                    }
                }

            val matchesAttackType = selectedAttackType == null ||
                (if (selectedAttackType == "Area Atk") enemy.isArea else !enemy.isArea)

            val matchesAbility = selectedAbilities.isEmpty() ||
                selectedAbilities.any { ability ->
                    enemy.abilities.any { it.contains(ability, ignoreCase = true) }
                }

            matchesQuery && matchesTrait && matchesAttackType && matchesAbility
        }
    }

    selectedEnemy?.let { enemy ->
        EnemyDetailDialog(
            enemy = enemy,
            onDismiss = { selectedEnemy = null },
        )
    }

    if (showFilterDialog) {
        EnemyFilterDialog(
            initialTraits = selectedTraits,
            initialAttackType = selectedAttackType,
            initialAbilities = selectedAbilities,
            allEnemies = allEnemies,
            onDismiss = { showFilterDialog = false },
            onApply = { t, at, a ->
                selectedTraits = t
                selectedAttackType = at
                selectedAbilities = a
            },
        )
    }

    val activeFilterCount = selectedTraits.size + (if (selectedAttackType != null) 1 else 0) + selectedAbilities.size
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = "Back",
                modifier = Modifier
                    .size(28.dp)
                    .clickable { onBack() },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Enemy Bestiary (${filteredEnemies.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = Ink,
            )
        }

        // Search Bar & Filter Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NyankoTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search enemy name, id or skill…",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilterButton(
                activeCount = activeFilterCount,
                onClick = { showFilterDialog = true },
            )
        }

        // Active Filter Chips Bar (e-commerce tags)
        if (activeFilterCount > 0) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                selectedTraits.forEach { trait ->
                    ActiveFilterTag(label = trait, onRemove = { selectedTraits = selectedTraits - trait })
                }
                selectedAttackType?.let { at ->
                    ActiveFilterTag(label = at, onRemove = { selectedAttackType = null })
                }
                selectedAbilities.forEach { ability ->
                    ActiveFilterTag(label = ability, onRemove = { selectedAbilities = selectedAbilities - ability })
                }
                Text(
                    text = "Clear all",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AccentDeep,
                    modifier = Modifier
                        .clickable {
                            selectedTraits = emptySet()
                            selectedAttackType = null
                            selectedAbilities = emptySet()
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Enemy List
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filteredEnemies, key = { it.id }) { enemy ->
                NyankoRow(onClick = { selectedEnemy = enemy }) {
                    EnemyIconView(context = context, enemyId = enemy.id, size = 44.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "E-${enemy.id.toString().padStart(3, '0')}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = AccentDeep,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (enemy.isArea) "Area Atk" else "Single Atk",
                                style = MaterialTheme.typography.labelSmall,
                                color = InkSoft,
                            )
                        }
                        Text(
                            text = enemy.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Range: ${enemy.range} | Speed: ${enemy.speed} | KB: ${enemy.knockbacks}",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSoft,
                            )
                        }
                        if (enemy.traits.isNotEmpty()) {
                            Text(
                                text = enemy.traits.joinToString(", "),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = AccentDeep,
                            )
                        }
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = InkSoft,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnemyFilterDialog(
    initialTraits: Set<String>,
    initialAttackType: String?,
    initialAbilities: Set<String>,
    allEnemies: List<EnemyStats>,
    onDismiss: () -> Unit,
    onApply: (Set<String>, String?, Set<String>) -> Unit,
) {
    var tempTraits by remember { mutableStateOf(initialTraits) }
    var tempAttackType by remember { mutableStateOf(initialAttackType) }
    var tempAbilities by remember { mutableStateOf(initialAbilities) }

    val previewCount = remember(tempTraits, tempAttackType, tempAbilities, allEnemies) {
        allEnemies.count { enemy ->
            val matchesTrait = tempTraits.isEmpty() ||
                tempTraits.any { trait ->
                    if (trait.equals("Traitless", ignoreCase = true)) {
                        enemy.traits.isEmpty() || enemy.traits.any { it.contains("Traitless", ignoreCase = true) }
                    } else {
                        enemy.traits.any { it.contains(trait, ignoreCase = true) }
                    }
                }
            val matchesAttackType = tempAttackType == null ||
                (if (tempAttackType == "Area Atk") enemy.isArea else !enemy.isArea)
            val matchesAbility = tempAbilities.isEmpty() ||
                tempAbilities.any { ability ->
                    enemy.abilities.any { it.contains(ability, ignoreCase = true) }
                }
            matchesTrait && matchesAttackType && matchesAbility
        }
    }

    NyankoDialogFrame(onDismiss = onDismiss) {
        DialogHeader(
            title = "Filter Enemies",
            subtitle = "$previewCount enemies match selected criteria",
        )
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            FilterSectionHeader("ENEMY TRAITS")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                enemyTraits.forEach { trait ->
                    val selected = tempTraits.contains(trait)
                    NyankoToggleChip(trait, selected) {
                        tempTraits = if (selected) tempTraits - trait else tempTraits + trait
                    }
                }
            }

            FilterSectionHeader("ATTACK TYPE")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                enemyAttackTypes.forEach { type ->
                    val selected = tempAttackType == type
                    NyankoToggleChip(type, selected) {
                        tempAttackType = if (selected) null else type
                    }
                }
            }

            FilterSectionHeader("ABILITIES & HAZARDS")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                enemyAbilities.forEach { ability ->
                    val selected = tempAbilities.contains(ability)
                    NyankoToggleChip(ability, selected) {
                        tempAbilities = if (selected) tempAbilities - ability else tempAbilities + ability
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NyankoPillButton(
                text = "Reset",
                onClick = {
                    tempTraits = emptySet()
                    tempAttackType = null
                    tempAbilities = emptySet()
                },
                filled = false,
                icon = null,
                modifier = Modifier.weight(1f),
            )
            NyankoPillButton(
                text = "Apply ($previewCount)",
                onClick = {
                    onApply(tempTraits, tempAttackType, tempAbilities)
                    onDismiss()
                },
                filled = true,
                icon = null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EnemyDetailDialog(
    enemy: EnemyStats,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    NyankoDialogFrame(onDismiss = onDismiss) {
        DialogHeader(
            title = enemy.name,
            subtitle = "Enemy #E-${enemy.id.toString().padStart(3, '0')}  ·  ${if (enemy.isArea) "Area attack" else "Single target"}",
        )
        Spacer(Modifier.height(12.dp))
        val shape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(PaperDeep)
                    .border(2.dp, Ink, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EnemyIconView(context = context, enemyId = enemy.id, size = 62.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = enemy.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = enemy.traitLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AccentDeep,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                StatPair("Range", enemy.range.toString(), "Speed", "${enemy.speed}/s")
                StatPair("Knockbacks", enemy.knockbacks.toString(), "Targets", if (enemy.isArea) "Area" else "Single")
                Spacer(Modifier.height(8.dp))
                TraitRow("Traits", enemy.traits.ifEmpty { listOf("Traitless") })
                if (enemy.abilities.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    TraitRow("Abilities", enemy.abilities)
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
private fun FilterButton(
    activeCount: Int,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val hasFilter = activeCount > 0
    Row(
        modifier = Modifier
            .height(52.dp)
            .clip(shape)
            .background(if (hasFilter) Accent else PaperLight)
            .border(2.dp, Ink, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_filter),
            contentDescription = "Filter",
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = if (hasFilter) "Filter ($activeCount)" else "Filter",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = Ink,
        )
    }
}

@Composable
private fun ActiveFilterTag(
    label: String,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(PaperDeep)
            .border(1.5.dp, Ink, shape)
            .clickable(onClick = onRemove)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Ink,
            fontSize = 12.sp,
        )
        Text(
            text = "✕",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = InkSoft,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun FilterSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Black,
        color = InkSoft,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun EnemyIconView(context: Context, enemyId: Int, size: Dp = 44.dp) {
    val bitmap = remember(enemyId) {
        runCatching {
            context.assets.open("enemies/enemy_${enemyId.toString().padStart(3, '0')}.png").use {
                android.graphics.BitmapFactory.decodeStream(it)?.asImageBitmap()
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(PaperDeep)
                .border(1.dp, Ink, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "E", fontWeight = FontWeight.Bold, color = InkSoft)
        }
    }
}
