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
        assertNull(CatScanResolver.parseLevel("123"))
    }

    @Test
    fun theIndexCoversEveryFormInTheBundle() {
        val expected = stats.values.sumOf { it.forms.size }
        // A handful of names are duplicated in the source data ("Cat Bros" is two units, and some
        // rows still carry placeholder names), so the index is slightly smaller than the form count.
        assertTrue("index unexpectedly small: ${index.size} vs $expected", index.size >= expected - 30)
        assertTrue("index unexpectedly large: ${index.size} vs $expected", index.size <= expected)
    }
}
