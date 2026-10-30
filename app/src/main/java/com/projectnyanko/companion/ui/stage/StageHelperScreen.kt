package com.projectnyanko.companion.ui.stage

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.projectnyanko.companion.BattleCatsApplication
import com.projectnyanko.companion.data.stage.StageCatalogRepository
import com.projectnyanko.companion.domain.stage.StageCatalog
import com.projectnyanko.companion.domain.stage.StageLocale
import com.projectnyanko.companion.domain.stage.EnemyStats
import com.projectnyanko.companion.domain.stage.StageRecord
import com.projectnyanko.companion.ui.theme.AccentDeep
import com.projectnyanko.companion.ui.theme.Danger
import com.projectnyanko.companion.ui.theme.Ink
import com.projectnyanko.companion.ui.theme.InkSoft
import com.projectnyanko.companion.ui.theme.NyankoPanel
import com.projectnyanko.companion.ui.theme.NyankoPillButton
import com.projectnyanko.companion.ui.theme.NyankoCaption
import com.projectnyanko.companion.ui.theme.NyankoRow
import com.projectnyanko.companion.ui.theme.NyankoTextField
import com.projectnyanko.companion.ui.theme.NyankoToggleChip
import com.projectnyanko.companion.ui.theme.PaperLight
import com.projectnyanko.companion.ui.theme.SectionHeading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val MAX_SEARCH_RESULTS = 12

@Composable
fun StageHelperScreen(catalogRepository: StageCatalogRepository) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiScope = rememberCoroutineScope()
    var region by remember { mutableStateOf(StageLocale.EN) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var captureActive by remember { mutableStateOf(StageCaptureService.isRunning) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StageRecord>>(emptyList()) }
    var selected by remember { mutableStateOf<StageRecord?>(null) }
    var catalog by remember { mutableStateOf<StageCatalog?>(null) }
    var searched by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                uiScope.launch {
                    repeat(12) {
                        captureActive = StageCaptureService.isProjectionActive
                        if (captureActive) return@launch
                        delay(250)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(catalogRepository) {
        catalog = withContext(Dispatchers.IO) {
            runCatching { catalogRepository.getCatalog() }.getOrNull()
        }
    }

    fun runSearch() {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            results = emptyList()
            searched = false
            return
        }
        searching = true
        uiScope.launch {
            val found = withContext(Dispatchers.IO) {
                catalog?.findCandidates(trimmed, region).orEmpty().take(MAX_SEARCH_RESULTS)
            }
            results = found
            selected = null
            searched = true
            searching = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NyankoPanel {
            SectionHeading(number = "1.", title = "Search for a stage")
            Spacer(Modifier.height(12.dp))
            NyankoTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Type stage name…",
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NyankoToggleChip("BCEN", region == StageLocale.EN) {
                    region = StageLocale.EN
                    persistRegion(context, StageLocale.EN)
                }
                NyankoToggleChip("BCJP", region == StageLocale.JP) {
                    region = StageLocale.JP
                    persistRegion(context, StageLocale.JP)
                }
            }
            Spacer(Modifier.height(12.dp))
            NyankoPillButton(
                text = if (searching) "Searching…" else "Search!",
                onClick = ::runSearch,
                enabled = !searching,
                modifier = Modifier.fillMaxWidth(),
            )
            if (searched && results.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                NyankoCaption("No local stage matches that name. Try fewer words or the other region.")
            }
            if (results.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    results.forEach { stage ->
                        NyankoRow(
                            highlight = stage.id == selected?.id,
                            onClick = { selected = stage },
                        ) {
                            Text(
                                text = stageName(stage, region),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            stage.energy?.let {
                                Text(
                                    text = "⚡ $it",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = InkSoft,
                                )
                            }
                        }
                    }
                }
            }
            selected?.let { stage ->
                Spacer(Modifier.height(12.dp))
                StageDetail(stage, region)
            }
        }

        NyankoPanel {
            SectionHeading(number = "2.", title = "Floating helper")
            Spacer(Modifier.height(10.dp))
            Text(
                text = when {
                    !overlayGranted -> "Allow this app to draw over Battle Cats, then start the helper."
                    captureActive -> "The paw is on and stays on. Tap it once per stage to read the screen; drag it to the bottom to stop."
                    else -> "Android asks once to share your screen — choose the entire screen. After that the paw stays on top of the game until you stop it, so this is a one-time approval per session."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = InkSoft,
            )
            Spacer(Modifier.height(14.dp))
            NyankoPillButton(
                text = when {
                    !overlayGranted -> "Allow floating paw"
                    captureActive -> "Spawn bubble"
                    else -> "Start helper"
                },
                onClick = {
                    if (Settings.canDrawOverlays(context)) {
                        overlayGranted = true
                        // Ask the service, not this screen's copy of the state. A stale `false` here
                        // used to send a live session back through the consent flow, and
                        // `requestConsent` silently does nothing when the projection is already up,
                        // so the press appeared to be ignored.
                        if (StageCaptureService.isProjectionActive) {
                            StageOverlayService.startBubble(context, region)
                            captureActive = true
                            statusMessage = "Floating paw is on. It captures one still only when tapped."
                        } else {
                            StageCaptureService.requestConsent(context, region)
                            statusMessage = "Approve Android's screen-share prompt and choose the whole screen."
                        }
                    } else {
                        statusMessage = "Allow display over other apps, then come back."
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (captureActive || overlayGranted) {
                Spacer(Modifier.height(8.dp))
                NyankoPillButton(
                    text = "Stop helper",
                    onClick = {
                        if (Settings.canDrawOverlays(context)) {
                            StageCaptureService.stop(context)
                            captureActive = false
                            statusMessage = "Stage helper stopped."
                        } else {
                            statusMessage = "No floating helper is running."
                        }
                    },
                    filled = false,
                    icon = null,
                    height = 46,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            statusMessage?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = AccentDeep)
            }
        }

    }
}

@Composable
private fun StageDetail(stage: StageRecord, region: StageLocale) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PaperLight, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stageName(stage, region),
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
        )
        val stats = listOfNotNull(
            stage.baseHealth?.let { "Base HP ${String.format(Locale.ROOT, "%,d", it)}" },
            stage.energy?.let { "Energy $it" },
            stage.difficulty.takeIf(String::isNotBlank)?.let { "Difficulty $it" },
        )
        if (stats.isNotEmpty()) {
            Text(stats.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = InkSoft)
        }
        val enemyIds = stage.enemySpawns.map { it.enemyId }.distinct()
        var inspectedEnemy by remember(stage.id) { mutableStateOf<Int?>(null) }
        if (enemyIds.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                enemyIds.take(30).forEach { enemyId ->
                    EnemyIcon(enemyId) { inspectedEnemy = enemyId }
                }
            }
            NyankoCaption("Tap an enemy for its stats")
        }
        inspectedEnemy?.let { enemyId ->
            EnemyStatsDialog(enemyId) { inspectedEnemy = null }
        }
    }
}

/**
 * Intrinsic enemy stats, shown over the stage card. Mirrors the overlay's enemy card so the same
 * information reads the same way whether the stage came from a scan or from a search.
 */
@Composable
private fun EnemyStatsDialog(enemyId: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = (context.applicationContext as BattleCatsApplication).enemyStatsRepository
    val stats by produceState<EnemyStats?>(initialValue = null, enemyId) {
        value = withContext(Dispatchers.IO) { repository.find(enemyId) }
    }
    val bitmap = remember(enemyId) { loadEnemyBitmap(context, enemyId) }

    Dialog(onDismissRequest = onDismiss) {
        NyankoPanel {
            Text(
                text = stats?.name ?: "Enemy $enemyId",
                style = MaterialTheme.typography.headlineMedium,
                color = Ink,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val detail = stats
                    if (detail == null) {
                        Text("Loading stats…", style = MaterialTheme.typography.bodyMedium, color = InkSoft)
                    } else {
                        Text(detail.traitLabel, style = MaterialTheme.typography.titleMedium, color = AccentDeep)
                        Text(
                            "${detail.attackLabel}  ·  Range ${detail.range}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink,
                        )
                        Text(
                            "Speed ${detail.speed}  ·  Knockbacks ${detail.knockbacks}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val abilities = stats?.abilities.orEmpty()
            if (abilities.isEmpty()) {
                Text("No special abilities", style = MaterialTheme.typography.bodyMedium, color = InkSoft)
            } else {
                Text("Abilities", style = MaterialTheme.typography.labelSmall, color = InkSoft)
                Text(abilities.joinToString("  ·  "), style = MaterialTheme.typography.bodyLarge, color = Ink)
            }
            Spacer(Modifier.height(12.dp))
            NyankoPillButton(
                text = "Close",
                onClick = onDismiss,
                icon = null,
                height = 46,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EnemyIcon(enemyId: Int, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(enemyId) { loadEnemyBitmap(context, enemyId) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Enemy $enemyId. Tap for its stats.",
            modifier = Modifier
                .size(36.dp)
                .clickable(onClick = onClick),
        )
    }
}

private fun loadEnemyBitmap(context: android.content.Context, enemyId: Int) = runCatching {
    context.assets.open("enemies/enemy_${String.format(Locale.ROOT, "%03d", enemyId)}.png")
        .use { BitmapFactory.decodeStream(it) }
}.getOrNull()

private fun stageName(stage: StageRecord, region: StageLocale): String =
    stage.names(region).firstOrNull()
        ?: stage.names(StageLocale.JP).firstOrNull()
        ?: "Unknown stage"

private fun persistRegion(context: android.content.Context, locale: StageLocale) {
    context.getSharedPreferences(StageOverlayService.PREFERENCES, 0)
        .edit { putString(StageOverlayService.PREFERENCE_REGION, locale.name) }
}
