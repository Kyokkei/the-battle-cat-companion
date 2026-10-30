package com.projectnyanko.companion.data.stage

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.projectnyanko.companion.BattleCatsApplication
import com.projectnyanko.companion.domain.stage.StageLocale
import com.projectnyanko.companion.domain.stage.StageMatcher
import com.projectnyanko.companion.ui.stage.StageOcrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Runs the real ONNX pipeline over real 2400x1080 BCEN captures.
 *
 * Every frame here is a stage-select screen that the shipped helper failed to read: the highlighted
 * card was clipped by a fixed crop band, or the read text had no English stage name to match because
 * of the translation join. They are kept as fixtures so neither can regress.
 */
class StageOcrFixtureTest {
    private data class Fixture(
        val asset: String,
        val expectedStageName: String,
    )

    private val fixtures = listOf(
        Fixture("stage-select-ai-fishing.jpg", "AI Fishing"),
        Fixture("stage-select-eager-entryway.jpg", "Eager Entryway"),
        Fixture("stage-select-sodden-bones.jpg", "Sodden Bones"),
        Fixture("stage-select-duel-prarie.jpg", "Duel on the Prarie (Vanguard)"),
        Fixture("stage-select-cosmos-urura.jpg", "Urura"),
    )

    @Test
    fun everySuppliedStageSelectFrameResolvesTheCentredCard() = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val application = ApplicationProvider.getApplicationContext<BattleCatsApplication>()
        val catalog = application.stageCatalogRepository.getCatalog()
        val ocrEngine = StageOcrEngine(application.stageOcrModelStore)
        try {
            fixtures.forEach { fixture ->
                val bitmap = testContext.assets.open(fixture.asset).use {
                    BitmapFactory.decodeStream(it) ?: error("Could not decode ${fixture.asset}")
                }
                try {
                    assertEquals("${fixture.asset} width", 2_400, bitmap.width)
                    assertEquals("${fixture.asset} height", 1_080, bitmap.height)
                    val lines = ocrEngine.recognize(bitmap, StageLocale.EN)
                    val match = StageMatcher.match(catalog, lines, StageLocale.EN)
                    val selected = match.likelySelected
                    assertNotNull("${fixture.asset} read ${lines.map { it.text }}", selected)
                    assertEquals(
                        "${fixture.asset} read ${lines.map { it.text }}",
                        fixture.expectedStageName,
                        selected!!.stage.names(StageLocale.EN).first(),
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            ocrEngine.close()
        }
    }
}
