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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectnyanko.companion.domain.stage.StageLocale
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
                Column(modifier = Modifier.fillMaxSize().background(Paper)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .statusBarsPadding(),
                    ) {
                        when (tab) {
                            0 -> StageHelperScreen(app.stageCatalogRepository)
                            else -> {
                                val viewModel: SeedTrackerViewModel = viewModel(
                                    factory = SeedTrackerViewModel.factory(
                                        app.seedTrackerRepository,
                                        app.gachaCatalogRepository,
                                        app.unitStatsRepository,
                                    ),
                                )
                                SeedTrackerScreen(viewModel)
                            }
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
        /**
         * The template also has an `Event` tab, but no event data is imported yet, so it is left out
         * rather than shipped as a dead button.
         */
        val TABS = listOf(
            Tab("Stage Info", R.drawable.nav_cat),
            Tab("Trackseeding", R.drawable.nav_search),
        )
    }
}
