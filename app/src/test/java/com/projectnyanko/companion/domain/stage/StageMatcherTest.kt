package com.projectnyanko.companion.domain.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageMatcherTest {
    @Test
    fun returnsAllExactCandidatesAndNeverAutoConfirmsASelection() {
        val first = stage(id = 1, names = listOf("Stage A"))
        val second = stage(id = 2, names = listOf("Stage B"))
        val catalog = catalog(listOf(first, second))

        val result = StageMatcher.match(
            catalog,
            listOf(
                OcrStageLine("Stage A", 0.98f, 0.2f, 0.1f, 0.3f, 0.4f),
                OcrStageLine("Stage B", 0.91f, 0.4f, 0.1f, 0.5f, 0.4f),
            ),
            StageLocale.EN,
        )

        assertEquals(listOf(1L, 2L), result.candidates.map { it.stage.id })
        assertTrue(result.requiresConfirmation)
        assertNull(result.confirmedStage)
    }

    @Test
    fun centeredCandidateIsSuggestedButStillRequiresUserConfirmation() {
        val neighbors = listOf(
            stage(id = 10, names = listOf("Left Stage")),
            stage(id = 11, names = listOf("AI Fishing")),
            stage(id = 12, names = listOf("Right Stage")),
        )
        val result = StageMatcher.match(
            catalog(neighbors),
            listOf(
                OcrStageLine("Left Stage", 0.99f, 0.10f, 0.10f, 0.20f, 0.25f),
                OcrStageLine("AI Fishing", 0.94f, 0.12f, 0.44f, 0.22f, 0.56f),
                OcrStageLine("Right Stage", 0.99f, 0.10f, 0.72f, 0.20f, 0.88f),
            ),
            StageLocale.EN,
        )

        assertEquals(11L, result.likelySelected?.stage?.id)
        assertTrue(result.requiresConfirmation)
        assertNull(result.confirmedStage)
    }

    @Test
    fun partialLineCanOnlyProduceUnconfirmedCandidates() {
        val stage = stage(id = 4, names = listOf("Ancient Moon Valley"))
        val result = StageMatcher.match(
            catalog(listOf(stage)),
            listOf(OcrStageLine("Ancient Moon Vall", 0.92f, 0.3f, 0.1f, 0.4f, 0.8f)),
            StageLocale.EN,
        )

        assertEquals(listOf(4L), result.candidates.map { it.stage.id })
        assertTrue(result.requiresConfirmation)
        assertNull(result.confirmedStage)
    }

    @Test
    fun ocrTyposStillFindTheCorrectStageAndCenteredCardSuggestion() {
        val target = stage(id = 9, names = listOf("Hole Lotta Somethin'"))
        val nearbyA = stage(id = 8, names = listOf("Chuo Road"))
        val nearbyB = stage(id = 10, names = listOf("So-So City Center"))
        val result = StageMatcher.match(
            catalog(listOf(nearbyA, target, nearbyB)),
            listOf(
                OcrStageLine("Chno Road", 0.75f, 0.10f, 0.05f, 0.18f, 0.20f),
                OcrStageLine("Hole Lotta Something?", 0.78f, 0.10f, 0.43f, 0.18f, 0.57f),
                OcrStageLine("So-Sb City Center", 0.75f, 0.10f, 0.74f, 0.18f, 0.95f),
            ),
            StageLocale.EN,
        )

        assertEquals(listOf(9L, 8L, 10L), result.candidates.map { it.stage.id })
        assertEquals(9L, result.likelySelected?.stage?.id)
    }

    @Test
    fun ppOcrAiTo40ConfusionStillSuggestsAiFishing() {
        val stage = stage(id = 14, names = listOf("AI Fishing"))
        val result = StageMatcher.match(
            catalog(listOf(stage)),
            listOf(OcrStageLine("40 fishine", 0.72f, 0.12f, 0.44f, 0.18f, 0.56f)),
            StageLocale.EN,
        )

        assertEquals(14L, result.likelySelected?.stage?.id)
        assertNull(result.confirmedStage)
    }

    @Test
    fun ocrExtraFinalCharacterStillResolvesButDoesNotAutoConfirm() {
        val stage = stage(id = 13, names = listOf("Hole Lotta Somethin'"))
        val result = StageMatcher.match(
            catalog(listOf(stage)),
            listOf(OcrStageLine("Hole Lotta Something?", 0.78f, 0.10f, 0.43f, 0.18f, 0.57f)),
            StageLocale.EN,
        )

        assertEquals(13L, result.likelySelected?.stage?.id)
        assertNull(result.confirmedStage)
    }

    @Test
    fun severalOcrSubstitutionsStillResolveTheSelectedStageName() {
        val stage = stage(id = 15, names = listOf("Snitches Get Stitches"))
        val result = StageMatcher.match(
            catalog(listOf(stage)),
            listOf(OcrStageLine("Snitches Oot Sutches", 0.89f, 0.10f, 0.44f, 0.18f, 0.57f)),
            StageLocale.EN,
        )

        assertEquals(15L, result.likelySelected?.stage?.id)
        assertNull(result.confirmedStage)
    }

    @Test
    fun nearbyPartialTextDoesNotMatchOrOverrideExactStageLabel() {
        val stage = stage(id = 3, names = listOf("Moonlit Valley"))
        val result = StageMatcher.match(
            catalog(listOf(stage)),
            listOf(
                OcrStageLine("Moonlit", 0.99f, 0.1f, 0.2f, 0.2f, 0.7f),
                OcrStageLine("Moonlit Valley", 0.20f, 0.3f, 0.2f, 0.4f, 0.7f),
            ),
            StageLocale.EN,
        )

        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun removedDifficultyTiersOfTheSameStageCollapseToTheExactName() {
        val base = stage(id = 20, names = listOf("Violet Flash"))
        val hard = stage(id = 21, names = listOf("Violet Flash (Hard)"))
        val expert = stage(id = 22, names = listOf("Violet Flash (Expert)"))
        val insane = stage(id = 23, names = listOf("Violet Flash (Insane)"))
        val result = StageMatcher.match(
            catalog(listOf(base, hard, expert, insane)),
            listOf(OcrStageLine("Violet Flash", 0.95f, 0.10f, 0.44f, 0.18f, 0.57f)),
            StageLocale.EN,
        )

        assertEquals(listOf(20L), result.candidates.map { it.stage.id })
        assertEquals(20L, result.likelySelected?.stage?.id)
    }

    @Test
    fun aSingleDifficultyTierStillResolvesWhenNoExactNameWasRead() {
        val hard = stage(id = 31, names = listOf("Violet Flash (Hard)"))
        val result = StageMatcher.match(
            catalog(listOf(hard)),
            listOf(OcrStageLine("Violet Flasb (Hard)", 0.90f, 0.10f, 0.44f, 0.18f, 0.57f)),
            StageLocale.EN,
        )

        assertEquals(listOf(31L), result.candidates.map { it.stage.id })
    }

    @Test
    fun aShortStageNameSurvivesOneMisreadGlyph() {
        // `Moon` is a real stage name and four characters long; at a five-character floor a single
        // misread made it unmatchable, which is why a chapter's final stage could not be read.
        val stage = stage(id = 16, names = listOf("Moon"))
        val result = StageMatcher.match(
            catalog(listOf(stage)),
            listOf(OcrStageLine("Mcon", 0.81f, 0.12f, 0.44f, 0.18f, 0.56f)),
            StageLocale.EN,
        )

        assertEquals(16L, result.likelySelected?.stage?.id)
    }

    @Test
    fun identicalNamesForDifferentStagesAreAllKept() {
        val heavenly = stage(id = 41, names = listOf("Floor 42"))
        val inferno = stage(id = 42, names = listOf("Floor 42"))
        val result = StageMatcher.match(
            catalog(listOf(heavenly, inferno)),
            listOf(OcrStageLine("Floor 42", 0.96f, 0.10f, 0.44f, 0.18f, 0.57f)),
            StageLocale.EN,
        )

        assertEquals(listOf(41L, 42L), result.candidates.map { it.stage.id })
    }


    private fun catalog(stages: List<StageRecord>) = StageCatalog(
        stages = stages,
        categories = emptyList(),
        metadata = StageCatalogMetadata(0, 0, 0, emptySet(), "fixture", "fixture", "fixture", emptyMap()),
    )

    private fun stage(id: Long, names: List<String>) = StageRecord(
        id = id,
        sourceId = id.toString(36),
        localizedNames = mapOf(StageLocale.EN to names),
        baseHealth = null,
        energy = null,
        length = null,
        difficulty = "",
        enemySpawns = emptyList(),
    )
}
