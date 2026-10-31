package com.projectnyanko.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectnyanko.companion.domain.stage.StageLocale
import com.projectnyanko.companion.ui.event.EventsScreen
import com.projectnyanko.companion.ui.seed.SeedTrackerScreen
import com.projectnyanko.companion.ui.seed.SeedTrackerViewModel
import com.projectnyanko.companion.ui.stage.StageCaptureService
import com.projectnyanko.companion.ui.stage.StageHelperScreen
import com.projectnyanko.companion.ui.stage.StageOverlayService
import com.projectnyanko.companion.ui.theme.BattleCatsTheme
import com.projectnyanko.companion.ui.theme.NavItem
import com.projectnyanko.companion.ui.theme.NyankoNavBar
import com.projectnyanko.companion.ui.theme.Paper

class MainActivity : ComponentActivity() {
    /** Applied before anything inflates, so the whole activity comes up in the chosen language. */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.projectnyanko.companion.data.locale.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BattleCatsApplication
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // Paper background behind both system bars, so the icons stay dark.
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        // Devices that are not edge-to-edge by default still paint the status bar from the theme,
        // which leaves a grey band above the paper.
        @Suppress("DEPRECATION")
        window.statusBarColor = Paper.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = Paper.toArgb()
        setContent {
            BattleCatsTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val context = androidx.compose.ui.platform.LocalContext.current
                var pendingUpdate by remember { mutableStateOf<com.projectnyanko.companion.data.update.UpdateInfo?>(null) }
                LaunchedEffect(Unit) {
                    // `am start … --ez preview_update true` shows the dialog without a release
                    // manifest, which is how the design gets checked on a device.
                    if (intent?.getBooleanExtra("preview_update", false) == true) {
                        pendingUpdate = com.projectnyanko.companion.data.update.UpdateInfo(
                            version = "0.2.0",
                            url = "https://example.com/battle-cats-companion",
                            notes = "Preview of the update prompt.",
                        )
                    } else if (com.projectnyanko.companion.data.update.UpdateChecker.isConfigured()) {
                        val current = runCatching {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }.getOrNull().orEmpty()
                        pendingUpdate = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.projectnyanko.companion.data.update.UpdateChecker.check(current)
                        }
                    }
                }
                pendingUpdate?.let { info ->
                    com.projectnyanko.companion.ui.update.UpdateDialog(
                        info = info,
                        onUpdateNow = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(info.url),
                                    ),
                                )
                            }
                            pendingUpdate = null
                        },
                        onLater = { pendingUpdate = null },
                    )
                }
                Column(modifier = Modifier.fillMaxSize().background(Paper)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .statusBarsPadding(),
                    ) {
                        when (tab) {
                            0 -> StageHelperScreen(app.stageCatalogRepository)
                            1 -> {
                                val viewModel: SeedTrackerViewModel = viewModel(
                                    factory = SeedTrackerViewModel.factory(
                                        app.seedTrackerRepository,
                                        app.gachaCatalogRepository,
                                        app.unitStatsRepository,
                                    ),
                                )
                                SeedTrackerScreen(viewModel)
                            }
                            else -> EventsScreen(app.eventScheduleRepository)
                        }
                    }
                    NyankoNavBar(
                        items = TABS.map { NavItem(it.label, it.icon) },
                        selectedIndex = tab,
                        onSelect = { tab = it },
                        modifier = Modifier.navigationBarsPadding(),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to this screen must never drop the bubble. The helper session is independent
        // of the activity, and hiding the bubble here made the first "Spawn bubble" look like it had
        // failed: the consent dialog returned, the bubble was hidden, and a second press was needed.
        if (StageCaptureService.isRunning) StageOverlayService.startBubble(this, savedRegion())
    }

    private fun savedRegion(): StageLocale =
        getSharedPreferences(StageOverlayService.PREFERENCES, MODE_PRIVATE)
            .getString(StageOverlayService.PREFERENCE_REGION, StageLocale.EN.name)
            ?.let { runCatching { StageLocale.valueOf(it) }.getOrNull() }
            ?: StageLocale.EN

    private data class Tab(
        val label: String,
        val icon: Int,
    )

    private companion object {
        /** The Event tab reads the schedule bundled from the wiki; see EventScheduleRepository. */
        val TABS = listOf(
            Tab("Stage Info", R.drawable.nav_cat),
            Tab("Trackseeding", R.drawable.nav_search),
            Tab("Event", R.drawable.ic_event),
        )
    }
}
