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
    @Test
    fun aMisreadCentreCardIsRecoveredFromItsNeighbours() {
        // Real geometry from a Feathergeddon III screen. The carousel draws I, II, III, Swan Song;
        // the selected label is yellow-on-white with a heavy outline and the recognizer drops a
        // stroke, so the centred card arrives as `Feathergeddon II` — which is the card already
        // sitting to its left. The neighbours make the answer unambiguous.
        val one = stage(id = 1, names = listOf("Feathergeddon I (Merciless)"))
        val two = stage(id = 2, names = listOf("Feathergeddon II (Merciless)"))
        val three = stage(id = 3, names = listOf("Feathergeddon III (Merciless)"))
        val swan = stage(id = 4, names = listOf("Feathergeddon: Swan Song (Brutal)"))
        val catalog = catalog(listOf(one, two, three, swan))

        val result = StageMatcher.match(
            catalog,
            listOf(
                OcrStageLine("Feathergeddon I (Merciless]", 0.95f, 0.138f, 0.134f, 0.173f, 0.229f),
                OcrStageLine("Feathergeddon II [Merciless]", 0.94f, 0.136f, 0.261f, 0.173f, 0.387f),
                OcrStageLine("Feathergeddon II (Merciless]", 0.95f, 0.146f, 0.422f, 0.191f, 0.578f),
                OcrStageLine("Feathergeddon: Swan Song [Brutal]", 0.96f, 0.134f, 0.610f, 0.174f, 0.738f),
            ),
            StageLocale.EN,
        )

        assertEquals(3L, result.likelySelected?.stage?.id)
        assertTrue(result.requiresConfirmation)
    }

    @Test
    fun neighboursThatAreNotTwoApartInferNothing() {
        // Two unrelated stages either side of the centre must not invent a third.
        val a = stage(id = 1, names = listOf("Alpha"))
        val b = stage(id = 2, names = listOf("Beta"))
        val z = stage(id = 9, names = listOf("Zeta"))
        val catalog = catalog(listOf(a, b, z))

        val result = StageMatcher.match(
            catalog,
            listOf(
                OcrStageLine("Alpha", 0.9f, 0.13f, 0.10f, 0.17f, 0.24f),
                OcrStageLine("Beta", 0.9f, 0.14f, 0.42f, 0.18f, 0.58f),
                OcrStageLine("Zeta", 0.9f, 0.13f, 0.70f, 0.17f, 0.80f),
            ),
            StageLocale.EN,
        )

        assertEquals(2L, result.likelySelected?.stage?.id)
    }
}
