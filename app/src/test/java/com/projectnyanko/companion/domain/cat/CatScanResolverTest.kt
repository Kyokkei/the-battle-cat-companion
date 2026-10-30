package com.projectnyanko.companion.domain.cat

import com.projectnyanko.companion.domain.stage.OcrStageLine
import com.projectnyanko.companion.domain.seed.TestCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name strings here are the literal output of the bundled PP-OCRv6 recognizer on the two
 * screenshots the user supplied, so a regression in matching shows up as a real failure.
 */
class CatScanResolverTest {
    private val stats by lazy { TestCatalog.unitStats() }
    private val index by lazy {
        CatNameIndex(
            stats.values.flatMap { unit ->
                unit.forms.mapIndexed { formIndex, form ->
                    CatNameEntry(unitId = unit.id, formIndex = formIndex, displayName = form.name)
                }
            },
        )
    }

    private fun line(
        text: String,
        confidence: Float,
        top: Float,
        bottom: Float,
        left: Float = 0.4f,
        right: Float = 0.6f,
    ) = OcrStageLine(text, confidence, top, left, bottom, right)

    @Test
    fun upgradeScreenResolvesTheSelectedUber() {
        // "Upgrade" detail card: name bar read at 0.974, level digits at 0.999.
        val lines = listOf(
            line("Upgrade", 0.95f, 0.02f, 0.08f),
            line("Uber Rare", 0.98f, 0.14f, 0.18f),
            line("Superstar Koneko", 0.974f, 0.13f, 0.20f),
            line("Max", 0.81f, 0.30f, 0.33f),
            line("Level", 0.79f, 0.33f, 0.36f),
            line("50", 0.999f, 0.30f, 0.37f, 0.56f, 0.63f),
        )
        val hit = requireNotNull(CatScanResolver.resolve(lines, index))
        assertEquals(783, hit.unitId)
        assertEquals(1, hit.formIndex)
        assertEquals("Superstar Koneko", hit.displayName)
        assertEquals(50, hit.level)
    }

    @Test
    fun organizeScreenResolvesADifferentUnitWithASimilarName() {
        // "Organize Cats" detail panel. Koneko 783 is NOT this cat: 873's evolved form is.
        val lines = listOf(
            line("Organize Cats", 0.97f, 0.02f, 0.08f),
            line("Sonic Siren Koneko", 1.0f, 0.47f, 0.53f),
            line("Max", 0.81f, 0.61f, 0.64f),
            line("Level", 0.79f, 0.64f, 0.67f),
            line("50", 0.982f, 0.61f, 0.69f, 0.56f, 0.63f),
        )
        val hit = requireNotNull(CatScanResolver.resolve(lines, index))
        assertEquals(873, hit.unitId)
        assertEquals(1, hit.formIndex)
        assertEquals(50, hit.level)
    }

    @Test
    fun aTruncatedNameStillResolves() {
        // The recognizer clipped the last character when the crop was too tight.
        val hit = requireNotNull(index.resolve("Sonic Siren Konek"))
        assertEquals(873, hit.entry.unitId)
        assertTrue("should have needed a correction", hit.distance > 0)
    }

    @Test
    fun aStageNameIsNotMistakenForACat() {
        val lines = listOf(
            line("The Great Abyss", 0.96f, 0.4f, 0.5f),
            line("Energy", 0.9f, 0.55f, 0.6f),
            line("120", 0.95f, 0.55f, 0.62f),
        )
        assertNull(CatScanResolver.resolve(lines, index))
    }

    @Test
    fun theLevelMustSitNearTheWordLevel() {
        val lines = listOf(
            line("Thief Cat", 0.97f, 0.20f, 0.26f),
            line("Level", 0.80f, 0.22f, 0.25f),
            // A stray number far away, e.g. the XP counter, must not be taken as the level.
            line("42", 0.99f, 0.90f, 0.95f),
        )
        val hit = requireNotNull(CatScanResolver.resolve(lines, index))
        assertNull(hit.level)
    }

    @Test
    fun aLoneNumberIsUsedWhenTheLabelWasNotRead() {
        val lines = listOf(
            line("Thief Cat", 0.97f, 0.20f, 0.26f),
            line("38", 0.99f, 0.28f, 0.33f),
        )
        assertEquals(38, requireNotNull(CatScanResolver.resolve(lines, index)).level)
    }

    @Test
    fun levelParsingUndoesCommonGlyphConfusions() {
        assertEquals(50, CatScanResolver.parseLevel("50"))
        assertEquals(50, CatScanResolver.parseLevel("5O"))
        assertEquals(10, CatScanResolver.parseLevel("l0"))
        assertNull(CatScanResolver.parseLevel("Max"))
        // Three digits are legal now that plus levels can push a cat past level 99.
        assertNull(CatScanResolver.parseLevel("1234"))
    }

    @Test
    fun theIndexCoversEveryFormInTheBundle() {
        val expected = stats.values.sumOf { it.forms.size }
        // A handful of names are duplicated in the source data ("Cat Bros" is two units, and some
        // rows still carry placeholder names), so the index is slightly smaller than the form count.
        assertTrue("index unexpectedly small: ${index.size} vs $expected", index.size >= expected - 30)
        assertTrue("index unexpectedly large: ${index.size} vs $expected", index.size <= expected)
    }

    @Test
    fun theMergedLabelOnANormalCatStillGivesTheLevel() {
        // Real read from the Mohawk Cat card: the label and the number arrive as one line and the
        // recognizer turns the final "l" into a "1".
        val lines = listOf(
            line("Characters", 0.97f, 0.10f, 0.15f),
            line("Mohawk Cat", 1.0f, 0.14f, 0.20f),
            line("Leve1 20", 0.672f, 0.28f, 0.36f, 0.49f, 0.55f),
        )
        val hit = requireNotNull(CatScanResolver.resolve(lines, index))
        assertEquals("Mohawk Cat", hit.displayName)
        // Unit 0 is Basic Cat, not "no unit". Treating 0 as missing silently hid every
        // Cat/Macho/Mohawk/Eraser/Dark scan.
        assertEquals(0, hit.unitId)
        assertEquals(20, hit.level)
        assertNull(hit.plusLevel)
        assertEquals(20, hit.effectiveLevel)
    }

    @Test
    fun plusLevelsAreReadAndAddedToTheProjectedLevel() {
        val lines = listOf(
            line("Mohawk Cat", 1.0f, 0.14f, 0.20f),
            line("Leve1 20", 0.672f, 0.28f, 0.33f, 0.49f, 0.55f),
            line("+90", 0.877f, 0.34f, 0.38f, 0.55f, 0.59f),
        )
        val hit = requireNotNull(CatScanResolver.resolve(lines, index))
        assertEquals(20, hit.level)
        assertEquals(90, hit.plusLevel)
        // Plus levels grow stats like normal levels.
        assertEquals(110, hit.effectiveLevel)
    }

    @Test
    fun aPlusLevelFarFromTheCardIsIgnored() {
        val lines = listOf(
            line("Mohawk Cat", 1.0f, 0.14f, 0.20f),
            line("Leve1 20", 0.672f, 0.28f, 0.33f, 0.49f, 0.55f),
            line("+12", 0.9f, 0.90f, 0.95f, 0.10f, 0.20f),
        )
        assertNull(requireNotNull(CatScanResolver.resolve(lines, index)).plusLevel)
    }

    @Test
    fun mergedLabelsAreParsed() {
        assertEquals(20, CatScanResolver.parseLevel("Leve1 20"))
        assertEquals(50, CatScanResolver.parseLevel("Max Level 50"))
        assertEquals(45, CatScanResolver.parseLevel("Level 45"))
        assertNull(CatScanResolver.parseLevel("Level"))
        assertNull(CatScanResolver.parseLevel("+90"))
        assertEquals(90, CatScanResolver.parsePlus("+90"))
        assertEquals(90, CatScanResolver.parsePlus("+9O"))
        assertNull(CatScanResolver.parsePlus("90"))
    }

    @Test
    fun theSelectedCatWinsWhenItsNeighboursAreAlsoOnScreen() {
        // Real failure: the Upgrade screen shows the neighbouring cats, and the resolver returned the
        // cat two slots to the right. The selected card prints its name larger.
        val lines = listOf(
            line("Mohawk Cat", 0.99f, 0.130f, 0.195f, 0.40f, 0.57f),
            line("Eraser Cat", 0.97f, 0.140f, 0.170f, 0.66f, 0.76f),
            line("Dark Cat", 0.98f, 0.140f, 0.172f, 0.82f, 0.91f),
        )
        val hit = requireNotNull(CatScanResolver.resolve(lines, index))
        assertEquals("Mohawk Cat", hit.displayName)
    }

    @Test
    fun theOrganizeRowDoesNotStealTheSelection() {
        // Organize Cats: a row of small names above, the chosen cat printed large in the panel below.
        val lines = listOf(
            line("Ninja Cat", 0.95f, 0.10f, 0.13f, 0.05f, 0.12f),
            line("Sumo Cat", 0.95f, 0.10f, 0.13f, 0.15f, 0.22f),
            line("Pastry Cat", 0.97f, 0.470f, 0.530f, 0.20f, 0.45f),
        )
        val hit = requireNotNull(CatScanResolver.resolve(lines, index))
        assertEquals("Pastry Cat", hit.displayName)
    }

    @Test
    fun equalSizedNamesFallBackToConfidenceThenLength() {
        val lines = listOf(
            line("Sumo Cat", 0.90f, 0.20f, 0.24f, 0.10f, 0.20f),
            line("Pastry Cat", 0.96f, 0.20f, 0.24f, 0.30f, 0.44f),
        )
        assertEquals("Pastry Cat", requireNotNull(CatScanResolver.resolve(lines, index)).displayName)
    }

    @Test
    fun aGarbledLabelIsRescuedByThePlusLevelUnderneath() {
        // Real read from the Mohawk card: the label came back as "eaxe 20", which matches no label
        // pattern, but "+90" printed directly below it.
        val lines = listOf(
            line("Mohawk Cat", 1.0f, 0.14f, 0.20f, 0.40f, 0.57f),
            line("eaxe 20", 0.52f, 0.280f, 0.320f, 0.49f, 0.55f),
            line("+90", 0.877f, 0.330f, 0.365f, 0.55f, 0.59f),
        )
        val hit = requireNotNull(CatScanResolver.resolve(lines, index))
        assertEquals(20, hit.level)
        assertEquals(90, hit.plusLevel)
        assertEquals(110, hit.effectiveLevel)
    }

    @Test
    fun aPlusWithNoNumberAboveItLeavesTheLevelUnknown() {
        val lines = listOf(
            line("Mohawk Cat", 1.0f, 0.14f, 0.20f, 0.40f, 0.57f),
            line("+90", 0.877f, 0.330f, 0.365f, 0.55f, 0.59f),
        )
        val hit = requireNotNull(CatScanResolver.resolve(lines, index))
        assertNull(hit.level)
        assertNull(hit.effectiveLevel)
    }

    @Test
    fun canCanCatReadsItsLevelWithoutAnyLevelLabel() {
        // Real card: name bar 0.42-0.55, "50" under it at 0.55-0.58, "+13" below that, and the
        // neighbour Roe Cat's "45" further right. The label was unreadable.
        val lines = listOf(
            line("Can Can Cat", 0.99f, 0.130f, 0.200f, 0.420f, 0.550f),
            line("50", 0.98f, 0.300f, 0.360f, 0.550f, 0.580f),
            line("+13", 0.95f, 0.360f, 0.400f, 0.550f, 0.590f),
            line("Roe Cat", 0.97f, 0.140f, 0.175f, 0.660f, 0.740f),
            line("45", 0.96f, 0.300f, 0.350f, 0.700f, 0.730f),
        )
        val hit = requireNotNull(CatScanResolver.resolve(lines, index))
        assertEquals("Can Can Cat", hit.displayName)
        assertEquals(50, hit.level)
        assertEquals(13, hit.plusLevel)
        assertEquals(63, hit.effectiveLevel)
    }

    @Test
    fun octopusCatReadsThirtyEightNotThree() {
        val lines = listOf(
            line("Octopus Cat", 0.99f, 0.130f, 0.200f, 0.420f, 0.550f),
            line("38", 0.98f, 0.300f, 0.360f, 0.550f, 0.580f),
            line("+1", 0.94f, 0.360f, 0.400f, 0.555f, 0.585f),
            line("iCat", 0.97f, 0.140f, 0.175f, 0.660f, 0.720f),
            line("30", 0.96f, 0.300f, 0.350f, 0.700f, 0.730f),
        )
        val hit = requireNotNull(CatScanResolver.resolve(lines, index))
        assertEquals("Octopus Cat", hit.displayName)
        assertEquals(38, hit.level)
        assertEquals(1, hit.plusLevel)
    }

    @Test
    fun aNeighboursLevelIsNotBorrowed() {
        val lines = listOf(
            line("Mohawk Cat", 0.99f, 0.130f, 0.200f, 0.420f, 0.550f),
            line("20", 0.98f, 0.300f, 0.360f, 0.550f, 0.580f),
            line("45", 0.98f, 0.300f, 0.360f, 0.700f, 0.730f),
        )
        assertEquals(20, requireNotNull(CatScanResolver.resolve(lines, index)).level)
    }
}
