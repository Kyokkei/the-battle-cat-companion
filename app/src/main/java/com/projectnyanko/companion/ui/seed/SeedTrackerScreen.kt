package com.projectnyanko.companion.ui.seed

import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectnyanko.companion.R
import com.projectnyanko.companion.data.local.SeedProfileEntity
import com.projectnyanko.companion.domain.seed.GameRegion
import com.projectnyanko.companion.domain.seed.TrackMarker
import com.projectnyanko.companion.ui.theme.Accent
import com.projectnyanko.companion.ui.theme.AccentDeep
import com.projectnyanko.companion.ui.theme.Danger
import com.projectnyanko.companion.ui.theme.Ink
import com.projectnyanko.companion.ui.theme.InkSoft
import com.projectnyanko.companion.ui.theme.NyankoCaption
import com.projectnyanko.companion.ui.theme.NyankoPanel
import com.projectnyanko.companion.ui.theme.NyankoPillButton
import com.projectnyanko.companion.ui.theme.NyankoTextField
import com.projectnyanko.companion.ui.theme.NyankoToggleChip
import com.projectnyanko.companion.ui.theme.PaperDeep

@Composable
fun SeedTrackerScreen(viewModel: SeedTrackerViewModel) {
    val tracker by viewModel.tracker.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val events by viewModel.events.collectAsState()
    val seek by viewModel.seek.collectAsState()
    val region by viewModel.region.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val undoDepth by viewModel.undoDepth.collectAsState()
    val stats by viewModel.stats.collectAsState()

    val openStats: (Int) -> Unit = viewModel::showStats
    var showSeek by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var changingBanner by remember { mutableStateOf(false) }

    // System back walks the screen's own stack instead of leaving the app: whatever is on top closes
    // first, and only the start screen lets back through.
    BackHandler(
        enabled = changingBanner || showSeek || showManual || stats != null || tracker is TrackerUiState.Ready,
    ) {
        when {
            changingBanner -> changingBanner = false
            showSeek -> {
                viewModel.closeSeek()
                showSeek = false
            }
            showManual -> showManual = false
            stats != null -> viewModel.hideStats()
            else -> viewModel.selectProfile(null)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = tracker) {
                TrackerUiState.Idle -> EmptyTrackerContent(
                    profiles = profiles,
                    region = region,
                    onRegionChange = viewModel::setRegion,
                    onFindSeed = {
                        viewModel.openSeek()
                        showSeek = true
                    },
                    onEnterSeed = { showManual = true },
                    onOpenProfile = viewModel::selectProfile,
                    onDeleteProfile = viewModel::deleteProfile,
                )

                TrackerUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = AccentDeep)
                }

                is TrackerUiState.Failed -> Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NyankoPanel {
                        Text(
                            "This banner cannot be simulated",
                            style = MaterialTheme.typography.titleMedium,
                            color = Danger,
                        )
                        Spacer(Modifier.height(6.dp))
                        NyankoCaption(state.message)
                        Spacer(Modifier.height(12.dp))
                        NyankoPillButton(
                            text = "Back to profiles",
                            onClick = { viewModel.selectProfile(null) },
                            filled = false,
                            icon = null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                is TrackerUiState.Ready -> ReadyTrackerContent(
                    state = state,
                    preview = preview,
                    region = region,
                    canUndo = undoDepth > 0,
                    onRegionChange = viewModel::setRegion,
                    onAdvance = viewModel::advanceTo,
                    onPreview = viewModel::previewPath,
                    onClearPreview = viewModel::clearPreview,
                    onStats = openStats,
                    onUndo = viewModel::undoLastRoll,
                    onForget = viewModel::forgetLastRoll,
                    onChangeBanner = { changingBanner = true },
                    onFindSeed = {
                        viewModel.openSeek()
                        showSeek = true
                    },
                    onClose = { viewModel.selectProfile(null) },
                )
            }
        }
    }

    if (showSeek) {
        SeedSeekDialog(
            state = seek,
            events = events,
            region = region,
            onRegionChange = viewModel::setRegion,
            onChooseEvent = viewModel::chooseSeekEvent,
            onSetRoll = viewModel::setSeekRoll,
            onClear = viewModel::clearSeekRolls,
            onStart = viewModel::startSeek,
            onCancelRun = viewModel::cancelSeek,
            onTrack = { name ->
                viewModel.trackSeekResult(name)
                showSeek = false
            },
            onDismiss = {
                viewModel.closeSeek()
                showSeek = false
            },
        )
    }

    if (showManual) {
        ManualSeedDialog(
            events = events,
            region = region,
            onRegionChange = viewModel::setRegion,
            onConfirm = { name, seed, event ->
                viewModel.createProfile(name, seed, event)
                showManual = false
            },
            onDismiss = { showManual = false },
        )
    }

    stats?.let { statsState ->
        CatStatsDialog(state = statsState, onDismiss = viewModel::hideStats)
    }

    val ready = tracker as? TrackerUiState.Ready
    if (changingBanner && ready != null) {
        BannerPickerDialog(
            events = events,
            selectedId = ready.profile.eventId,
            region = region,
            onRegionChange = viewModel::setRegion,
            onSelect = { event ->
                changingBanner = false
                viewModel.changeBanner(ready.profile, event)
            },
            onDismiss = { changingBanner = false },
        )
    }
}

// ------------------------------------------------------------------------------ empty state

@Composable
private fun EmptyTrackerContent(
    profiles: List<SeedProfileEntity>,
    region: GameRegion,
    onRegionChange: (GameRegion) -> Unit,
    onFindSeed: () -> Unit,
    onEnterSeed: () -> Unit,
    onOpenProfile: (Long) -> Unit,
    onDeleteProfile: (SeedProfileEntity) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NyankoPanel {
            Text("Seed tracker", style = MaterialTheme.typography.headlineMedium, color = Ink)
            Spacer(Modifier.height(6.dp))
            NyankoCaption(stringResource(R.string.seed_intro))
            Spacer(Modifier.height(12.dp))
            RegionChips(region, onRegionChange)
            Spacer(Modifier.height(14.dp))
            NyankoPillButton(
                text = "Find my seed",
                onClick = onFindSeed,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            NyankoPillButton(
                text = "I already know my seed",
                onClick = onEnterSeed,
                filled = false,
                icon = null,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            NyankoCaption(stringResource(R.string.seed_footer))
        }

        if (profiles.isNotEmpty()) {
            Text(
                "Saved seeds",
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                modifier = Modifier.padding(start = 4.dp),
            )
            profiles.forEach { profile ->
                NyankoPanel(contentPadding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium, color = Ink)
                            Text(
                                text = "${profile.seed}  ·  ${profile.region}  ·  ${profile.eventId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = InkSoft,
                            )
                        }
                        TextButton(onClick = { onOpenProfile(profile.id) }) { Text("Open") }
                        TextButton(onClick = { onDeleteProfile(profile) }) { Text("Delete", color = Danger) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionChips(region: GameRegion, onRegionChange: (GameRegion) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NyankoToggleChip("BCEN", region == GameRegion.EN) { onRegionChange(GameRegion.EN) }
        NyankoToggleChip("BCJP", region == GameRegion.JP) { onRegionChange(GameRegion.JP) }
    }
}

// ------------------------------------------------------------------------------ tracker

@Composable
private fun ReadyTrackerContent(
    state: TrackerUiState.Ready,
    preview: PreviewState,
    region: GameRegion,
    canUndo: Boolean,
    onRegionChange: (GameRegion) -> Unit,
    onAdvance: (TrackMarker) -> Unit,
    onPreview: (TrackMarker) -> Unit,
    onClearPreview: () -> Unit,
    onStats: (Int) -> Unit,
    onUndo: () -> Unit,
    onForget: () -> Unit,
    onChangeBanner: () -> Unit,
    onFindSeed: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SeedHeaderCard(
            state = state,
            region = region,
            canUndo = canUndo,
            onRegionChange = onRegionChange,
            onUndo = onUndo,
            onForget = onForget,
            onChangeBanner = onChangeBanner,
            onFindSeed = onFindSeed,
            onClose = onClose,
        )
        when {
            preview.unreachable -> PreviewBanner(
                text = "${preview.targetName} is not on the route from here — you would have to " +
                    "reach it from a different cat.",
                onDismiss = onClearPreview,
            )

            preview.path.isNotEmpty() -> PreviewBanner(
                text = "Path to ${preview.targetName}: ${preview.path.size} rolls" +
                    (preview.next?.let { ", then → $it" } ?: ", then the table ends") + ".",
                onDismiss = onClearPreview,
            )
        }
        TableHeader()
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.rows, key = { it.sequence }) { row ->
                TrackRowView(
                    row = row,
                    nextMarker = state.nextMarker,
                    preview = preview,
                    showGuaranteed = state.guaranteedRolls > 0,
                    onAdvance = onAdvance,
                    onPreview = onPreview,
                    onStats = onStats,
                )
            }
            item {
                Text(
                    text = "Tap a cat's name to record that you rolled it — the table then restarts " +
                        "from the next roll. Tap anywhere else in a cell to preview the route there.",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewBanner(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Accent.copy(alpha = 0.35f))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Ink,
            modifier = Modifier.weight(1f),
        )
        Text("✕", style = MaterialTheme.typography.labelSmall, color = InkSoft)
    }
}

@Composable
private fun SeedHeaderCard(
    state: TrackerUiState.Ready,
    region: GameRegion,
    canUndo: Boolean,
    onRegionChange: (GameRegion) -> Unit,
    onUndo: () -> Unit,
    onForget: () -> Unit,
    onChangeBanner: () -> Unit,
    onFindSeed: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PaperDeep)
            .border(2.dp, Ink, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("SEED", style = MaterialTheme.typography.labelSmall, color = InkSoft)
                Text(
                    text = state.profile.seed.toString(),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 22.sp,
                    ),
                    color = Ink,
                    maxLines = 1,
                )
            }
            RegionChips(region, onRegionChange)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("LAST ROLL", style = MaterialTheme.typography.labelSmall, color = InkSoft)
                Text(
                    text = state.lastRollName.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("NEXT ROLL", style = MaterialTheme.typography.labelSmall, color = InkSoft)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MarkerBadge(state.nextLabel.ifEmpty { "—" })
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = state.nextName.ifEmpty { "end of table" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.event.name,
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.guaranteedRolls > 0) {
                MicroChip("${state.guaranteedRolls}-roll guarantee")
                Spacer(Modifier.width(6.dp))
            }
            HeaderAction("Undo", enabled = canUndo, onClick = onUndo)
            HeaderAction("Forget", onClick = onForget)
            HeaderAction("Banner", onClick = onChangeBanner)
            HeaderAction("Find seed", onClick = onFindSeed)
            Spacer(Modifier.weight(1f))
            HeaderAction("Close", onClick = onClose)
        }
    }
}

@Composable
private fun HeaderAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (enabled) AccentDeep else InkSoft.copy(alpha = 0.5f),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

@Composable
private fun TableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "No.",
            style = MaterialTheme.typography.labelSmall,
            color = InkSoft,
            modifier = Modifier.width(34.dp),
        )
        ColumnLabel("Track A", Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        ColumnLabel("Track B", Modifier.weight(1f))
    }
}

@Composable
private fun ColumnLabel(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Ink.copy(alpha = 0.06f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = InkSoft)
    }
}

@Composable
private fun TrackRowView(
    row: TrackRow,
    nextMarker: TrackMarker?,
    preview: PreviewState,
    showGuaranteed: Boolean,
    onAdvance: (TrackMarker) -> Unit,
    onPreview: (TrackMarker) -> Unit,
    onStats: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.width(34.dp).padding(top = 8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = row.sequence.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = InkSoft,
            )
        }
        TrackCellView(
            cell = row.trackA,
            reroll = row.rerollA,
            guaranteed = row.guaranteedA.takeIf { showGuaranteed },
            nextMarker = nextMarker,
            preview = preview,
            modifier = Modifier.weight(1f),
            onAdvance = onAdvance,
            onPreview = onPreview,
            onStats = onStats,
        )
        Spacer(Modifier.width(6.dp))
        TrackCellView(
            cell = row.trackB,
            reroll = row.rerollB,
            guaranteed = row.guaranteedB.takeIf { showGuaranteed },
            nextMarker = nextMarker,
            preview = preview,
            modifier = Modifier.weight(1f),
            onAdvance = onAdvance,
            onPreview = onPreview,
            onStats = onStats,
        )
    }
}

/** One cell of the grid: a rounded box tinted by rarity, filled when it is the next roll. */
@Composable
private fun TrackCellView(
    cell: TrackCell,
    reroll: TrackCell?,
    guaranteed: TrackCell?,
    nextMarker: TrackMarker?,
    preview: PreviewState,
    modifier: Modifier = Modifier,
    onAdvance: (TrackMarker) -> Unit,
    onPreview: (TrackMarker) -> Unit,
    onStats: (Int) -> Unit,
) {
    val isNextCell = nextMarker == cell.marker || nextMarker == reroll?.marker ||
        nextMarker == guaranteed?.marker
    val shape = RoundedCornerShape(12.dp)
    val background = when {
        isNextCell -> Accent
        else -> rarityColor(cell.rarity).copy(alpha = 0.10f)
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(if (isNextCell) 2.dp else 1.dp, Ink.copy(alpha = if (isNextCell) 1f else 0.22f), shape)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CatLine(
            cell = cell,
            muted = reroll != null,
            isNext = nextMarker == cell.marker,
            preview = preview,
            onAdvance = onAdvance,
            onPreview = onPreview,
            onStats = onStats,
        )
        reroll?.let {
            JumpLine(
                cell = it,
                prefix = "",
                isNext = nextMarker == it.marker,
                preview = preview,
                onAdvance = onAdvance,
                onPreview = onPreview,
                onStats = onStats,
            )
        }
        guaranteed?.let {
            JumpLine(
                cell = it,
                prefix = "G",
                isNext = nextMarker == it.marker,
                preview = preview,
                onAdvance = onAdvance,
                onPreview = onPreview,
                onStats = onStats,
            )
        }
    }
}

@Composable
private fun CatLine(
    cell: TrackCell,
    muted: Boolean,
    isNext: Boolean,
    preview: PreviewState,
    onAdvance: (TrackMarker) -> Unit,
    onPreview: (TrackMarker) -> Unit,
    onStats: (Int) -> Unit,
) {
    val inPath = preview.path.contains(cell.marker)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    preview.next == cell.marker -> Accent.copy(alpha = 0.75f)
                    inPath -> Accent.copy(alpha = 0.35f)
                    else -> Color.Transparent
                },
            )
            .clickable { onPreview(cell.marker) }
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RarityDot(cell.rarity)
        Spacer(Modifier.width(5.dp))
        Text(
            text = cell.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (muted) InkSoft.copy(alpha = 0.55f) else Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .clickable { onAdvance(cell.marker) }
                .padding(vertical = 1.dp),
        )
        if (isNext) {
            Spacer(Modifier.width(4.dp))
            MicroChip("next")
        }
        PawButton(cell.unitId, onStats)
    }
}

@Composable
private fun JumpLine(
    cell: TrackCell,
    prefix: String,
    isNext: Boolean,
    preview: PreviewState,
    onAdvance: (TrackMarker) -> Unit,
    onPreview: (TrackMarker) -> Unit,
    onStats: (Int) -> Unit,
) {
    val inPath = preview.path.contains(cell.marker)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    preview.next == cell.marker -> Accent.copy(alpha = 0.75f)
                    inPath -> Accent.copy(alpha = 0.35f)
                    else -> Color.Transparent
                },
            )
            .clickable { onPreview(cell.marker) }
            .padding(start = 8.dp, end = 2.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isNext) {
            Text(
                text = "▶",
                style = MaterialTheme.typography.labelSmall,
                color = AccentDeep,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.width(3.dp))
        }
        if (prefix.isNotEmpty()) {
            Text(
                text = prefix,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                color = AccentDeep,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = cell.name,
            style = MaterialTheme.typography.labelSmall,
            color = AccentDeep,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .clickable { onAdvance(cell.marker) }
                .padding(vertical = 1.dp),
        )
        // The destination gets its own slot so a long name can never push it off the cell.
        cell.jumpTarget?.let { target ->
            Spacer(Modifier.width(3.dp))
            Text(
                text = if (cell.jumpRight) "→ $target" else "← $target",
                style = MaterialTheme.typography.labelSmall,
                color = AccentDeep,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
        }
        PawButton(cell.unitId, onStats)
    }
}

/** The little paw from the web tracker: opens that cat's stat page. */
@Composable
private fun PawButton(unitId: Int, onStats: (Int) -> Unit) {
    if (unitId < 0) return
    Image(
        painter = painterResource(R.drawable.icon_paw),
        contentDescription = "Cat stats",
        modifier = Modifier
            .padding(start = 3.dp)
            .size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable { onStats(unitId) },
    )
}

// ------------------------------------------------------------------------------ manual entry

@Composable
private fun ManualSeedDialog(
    events: List<EventOption>,
    region: GameRegion,
    onRegionChange: (GameRegion) -> Unit,
    onConfirm: (String, Long, EventOption) -> Unit,
    onDismiss: () -> Unit,
) {
    var seedText by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var event by remember { mutableStateOf<EventOption?>(null) }
    var pickingBanner by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (pickingBanner) {
        BannerPickerDialog(
            events = events,
            selectedId = event?.eventId,
            region = region,
            onRegionChange = onRegionChange,
            onSelect = {
                event = it
                pickingBanner = false
            },
            onDismiss = { pickingBanner = false },
        )
        return
    }

    NyankoDialogFrame(onDismiss = onDismiss, fillHeight = false) {
        DialogHeader(
            title = "I know my seed",
            subtitle = "Paste the number from the web tracker, or the one you saved earlier.",
        )
        Spacer(Modifier.height(14.dp))
        NyankoTextField(
            value = seedText,
            onValueChange = { value ->
                seedText = value.filter(Char::isDigit).take(10)
                error = null
            },
            placeholder = "1234567890",
            label = "Seed (1 – 4,294,967,295)",
            keyboardType = KeyboardType.Number,
            leadingIcon = null,
        )
        Spacer(Modifier.height(10.dp))
        NyankoTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = "My seed",
            label = "Profile name",
            leadingIcon = null,
        )
        Spacer(Modifier.height(12.dp))
        BannerSelectorRow(event = event, onChange = { pickingBanner = true })
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Danger)
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NyankoPillButton(
                text = "Cancel",
                onClick = onDismiss,
                filled = false,
                icon = null,
                modifier = Modifier.weight(1f),
            )
            NyankoPillButton(
                text = "Track",
                onClick = {
                    val parsed = seedText.toLongOrNull()
                    val chosen = event
                    error = when {
                        parsed == null || parsed !in 1L..4_294_967_295L ->
                            "Enter a seed between 1 and 4,294,967,295."
                        chosen == null -> "Pick the banner you want to track."
                        else -> null
                    }
                    if (error == null) onConfirm(name, parsed!!, chosen!!)
                },
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}
