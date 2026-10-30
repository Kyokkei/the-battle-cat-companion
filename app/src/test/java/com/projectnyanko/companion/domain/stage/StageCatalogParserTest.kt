package com.projectnyanko.companion.domain.stage

import com.projectnyanko.companion.data.stage.StageCatalogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class StageCatalogParserTest {
    @Test
    fun suppliedCatalogLoadsAllStageRowsAndPreservesMissingSpriteReferences() {
        val root = assetsRoot()
        val spriteIds = Files.list(root.resolve("enemies")).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.matches(Regex("enemy_\\d+\\.png")) }
                .map { it.removePrefix("enemy_").removeSuffix(".png").toInt() }
                .toList()
                .toSet()
        }
        val catalog = parse(root, spriteIds)

        assertEquals(6_328, catalog.metadata.stageCount)
        assertEquals(6_924, catalog.metadata.translationRowCount)
        assertEquals(746, catalog.metadata.enemySpriteCount)
        assertEquals(43, catalog.metadata.missingEnemySpriteIds.size)
        assertEquals(catalog.metadata.stageCount, catalog.stages.map { it.id }.toSet().size)
        assertEquals("CC BY-SA 4.0", catalog.metadata.license)
        assertTrue(catalog.stages.any { stage -> stage.enemySpawns.any { it.magnification != null } })
        assertTrue(catalog.categories.isNotEmpty())
    }

    @Test
    fun stageNameJoinUsesAllLocalesAndKeepsAmbiguousNamesAsCandidates() {
        val root = assetsRoot()
        val sprites = Files.list(root.resolve("enemies")).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.matches(Regex("enemy_\\d+\\.png")) }
                .map { it.removePrefix("enemy_").removeSuffix(".png").toInt() }
                .toList()
                .toSet()
        }
        val catalog = parse(root, sprites)
        val stage = catalog.stages.first { it.sourceId == "0" }

        assertTrue(stage.names(StageLocale.JP).isNotEmpty())
        assertTrue(stage.names(StageLocale.TW).isNotEmpty())
        assertFalse(stage.names(StageLocale.EN).isEmpty())
        assertEquals("Earthshaker", stage.names(StageLocale.EN).first())
        assertEquals(60_000L, stage.baseHealth)
        assertEquals(50L, stage.energy)
        assertNotNull(catalog.findById(stage.id))
        assertTrue(catalog.findCandidates(stage.names(StageLocale.EN).first(), StageLocale.EN).isNotEmpty())
    }

    @Test
    fun aiFishingScreenshotLabelResolvesToTheCorrectStageRecord() {
        val root = assetsRoot()
        val spriteIds = Files.list(root.resolve("enemies")).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.matches(Regex("enemy_\\d+\\.png")) }
                .map { it.removePrefix("enemy_").removeSuffix(".png").toInt() }
                .toList()
                .toSet()
        }
        val catalog = parse(root, spriteIds)
        val candidates = catalog.findCandidates("AI FISHING", StageLocale.EN)

        assertEquals(listOf("9izzu"), candidates.map { it.sourceId })
        assertEquals("AI Fishing", candidates.single().names(StageLocale.EN).first())
    }

    @Test
    fun screenReadHoleLottaSomethingTypoFindsHoleLottaSomethin() {
        val root = assetsRoot()
        val spriteIds = Files.list(root.resolve("enemies")).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.matches(Regex("enemy_\\d+\\.png")) }
                .map { it.removePrefix("enemy_").removeSuffix(".png").toInt() }
                .toList()
                .toSet()
        }
        val catalog = parse(root, spriteIds)
        val candidates = catalog.findCandidates("Hole Lotta Something?", StageLocale.EN)

        assertEquals(listOf("9j0rr"), candidates.map { it.sourceId })
        assertEquals("Hole Lotta Somethin'", candidates.single().names(StageLocale.EN).first())
    }

    @Test
    fun storyMapScreenshotReadsResolveToTheRightStage() {
        val root = assetsRoot()
        val spriteIds = Files.list(root.resolve("enemies")).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.matches(Regex("enemy_\\d+\\.png")) }
                .map { it.removePrefix("enemy_").removeSuffix(".png").toInt() }
                .toList()
                .toSet()
        }
        val catalog = parse(root, spriteIds)

        // Raw reads from a real 2400x1080 story-map stage select frame; each must land on the card
        // that is actually on screen, including the second card that OCR mangled.
        assertEquals(
            listOf("Downpour Jungle Area 16"),
            catalog.findCandidates("Cownpo r Jungle Area 16", StageLocale.EN).map { it.names(StageLocale.EN).first() },
        )
        assertEquals(
            listOf("Stormrent Forest Area: Final"),
            catalog.findCandidates("Stormrent Forest Area8 Fin!", StageLocale.EN).map { it.names(StageLocale.EN).first() },
        )
        assertEquals(
            listOf("Primeval Wood Area 18"),
            catalog.findCandidates("Primeval Wood Area 18", StageLocale.EN).map { it.names(StageLocale.EN).first() },
        )
    }

    @Test
    fun towerFloorsAreToldApartByTheirTower() {
        val root = assetsRoot()
        val spriteIds = Files.list(root.resolve("enemies")).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.matches(Regex("enemy_\\d+\\.png")) }
                .map { it.removePrefix("enemy_").removeSuffix(".png").toInt() }
                .toList()
                .toSet()
        }
        val catalog = parse(root, spriteIds)
        val floor42 = catalog.findCandidates("Floor 42", StageLocale.EN)
        val labels = floor42.associate { stage -> catalog.seriesNameFor(stage) to stage.names(StageLocale.EN).first() }

        assertEquals(setOf("Heavenly Tower", "Inferno Tower", "Heavenly Tower (Alt)"), labels.keys)
        assertTrue(labels.values.all { it == "Floor 42" })
    }

    @Test
    fun aTowerFloorDoesNotOfferTheFloorWithFewerDigits() {
        val root = assetsRoot()
        val spriteIds = Files.list(root.resolve("enemies")).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.matches(Regex("enemy_\\d+\\.png")) }
                .map { it.removePrefix("enemy_").removeSuffix(".png").toInt() }
                .toList()
                .toSet()
        }
        val catalog = parse(root, spriteIds)

        assertEquals(
            setOf("Floor 42"),
            catalog.findCandidates("Floor 42", StageLocale.EN).map { it.names(StageLocale.EN).first() }.toSet(),
        )
    }

    @Test
    fun stagesWhoseTranslationRowListsJapaneseAndChineseInSwappedColumnsStillGetEnglishNames() {
        val root = assetsRoot()
        val spriteIds = Files.list(root.resolve("enemies")).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.matches(Regex("enemy_\\d+\\.png")) }
                .map { it.removePrefix("enemy_").removeSuffix(".png").toInt() }
                .toList()
                .toSet()
        }
        val catalog = parse(root, spriteIds)

        // `tstage.tsv` lists these Cat of the Cosmos stages as jp=魯神五號 / tw=ウラルー, the opposite
        // columns from `stage.tsv`. The exact-pair join missed, the stages ended up with no English
        // name at all, and the OCR could read the card perfectly while the matcher found nothing.
        assertEquals(
            setOf("Urura"),
            catalog.findCandidates("Urura", StageLocale.EN).map { it.names(StageLocale.EN).first() }.toSet(),
        )
        assertTrue(catalog.findCandidates("Urura", StageLocale.EN).size > 1)
        assertEquals(
            setOf("Thanxbye"),
            catalog.findCandidates("Thanxbye", StageLocale.EN).map { it.names(StageLocale.EN).first() }.toSet(),
        )
        assertEquals(
            setOf("Aguham"),
            catalog.findCandidates("Aguham", StageLocale.EN).map { it.names(StageLocale.EN).first() }.toSet(),
        )
        assertEquals(
            setOf("Somolon"),
            catalog.findCandidates("Somolon", StageLocale.EN).map { it.names(StageLocale.EN).first() }.toSet(),
        )
    }

    @Test
    fun everyStageExceptTheUntranslatedEventRowsHasAnEnglishName() {
        val root = assetsRoot()
        val spriteIds = Files.list(root.resolve("enemies")).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.matches(Regex("enemy_\\d+\\.png")) }
                .map { it.removePrefix("enemy_").removeSuffix(".png").toInt() }
                .toList()
                .toSet()
        }
        val catalog = parse(root, spriteIds)

        // Was 1,221 stages without an English name; the reversed-pair and single-name fallbacks
        // recover 764 of them. The remainder are untranslated collaboration/event rows that carry
        // only a Japanese name, so they stay reachable in JP only.
        assertEquals(454, catalog.stages.count { it.names(StageLocale.EN).isEmpty() })
    }

    @Test
    fun chapterLabelsTellIdenticallyNamedStagesApart() {
        val root = assetsRoot()
        val spriteIds = Files.list(root.resolve("enemies")).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.matches(Regex("enemy_\\d+\\.png")) }
                .map { it.removePrefix("enemy_").removeSuffix(".png").toInt() }
                .toList()
                .toSet()
        }
        val catalog = parse(root, spriteIds)
        val southAfricas = catalog.findCandidates("South Africa", StageLocale.EN)
        val chapters = southAfricas.mapNotNull { catalog.chapterNameFor(it) }.toSet()

        // Eleven records share the name; the labels are what let a player pick the one they can see.
        assertTrue(chapters.contains("Aku Realms"))
        assertTrue(chapters.contains("Empire of Cats"))
        assertTrue(chapters.size > 1)
        // Every labelled record sits in its own chapter, so a label identifies one record.
        assertEquals(
            southAfricas.mapNotNull { catalog.chapterNameFor(it) }.size,
            chapters.size,
        )
    }

    @Test
    fun energyIsBase36SoTheFutureMoonsReadEightyNinetyHundred() {
        val root = assetsRoot()
        val spriteIds = Files.list(root.resolve("enemies")).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.matches(Regex("enemy_\\d+\\.png")) }
                .map { it.removePrefix("enemy_").removeSuffix(".png").toInt() }
                .toList()
                .toSet()
        }
        val catalog = parse(root, spriteIds)

        // `28` is base 36 for 80, not decimal 28. Reading energy as decimal first made every
        // digit-only value wrong, which shifted the cost of 1,446 stages.
        val moons = catalog.findCandidates("Moon", StageLocale.EN)
            .mapNotNull { stage -> catalog.chapterNameFor(stage)?.let { it to stage.energy } }
            .toMap()

        assertEquals(80L, moons["Into the Future Ch.1"])
        assertEquals(90L, moons["Into the Future Ch.2"])
        assertEquals(100L, moons["Into the Future Ch.3"])
    }

    @Test
    fun theEmpireOfCatsMoonsResolveThroughTheirChapterSuffix() {
        val root = assetsRoot()
        val spriteIds = Files.list(root.resolve("enemies")).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.matches(Regex("enemy_\\d+\\.png")) }
                .map { it.removePrefix("enemy_").removeSuffix(".png").toInt() }
                .toList()
                .toSet()
        }
        val catalog = parse(root, spriteIds)

        // Map 3000's stages 47, 49 and 50 carry `西表島 第N章` in the Japanese column while the
        // Traditional-Chinese column says `月球 第N章` — they are the three Empire of Cats Moons, but
        // the translation table only knows `西表島` / `月球`, so the exact join found nothing and the
        // stages were listed under their Japanese collab label instead of `Moon`.
        val eocMoons = catalog.findCandidates("Moon", StageLocale.EN)
            .filter { catalog.chapterNameFor(it) in EOC_MOON_CHAPTERS }
            .sortedBy { it.id }

        assertEquals(3, eocMoons.size)
        assertEquals(listOf("Moon", "Moon", "Moon"), eocMoons.map { it.names(StageLocale.EN).first() })
        assertEquals(
            listOf("Empire of Cats Ch.1", "Empire of Cats Ch.2", "Empire of Cats Ch.3"),
            eocMoons.map { catalog.chapterNameFor(it) },
        )
    }

    @Test
    fun invalidBase36EnemyIdIsRejected() {
        val error = runCatching {
            StageCatalogParser.parse(
                stageTsv = "id\tname_tw\tname_jp\thealth\tenergy\tlen\tenemy_lines\nd\t\t\t0\t0\t0\tbad!,1,0,1,1,100,0,5k".toByteArray(),
                translationTsv = "jp\ttw\ten\tkr\n".toByteArray(),
                schemeJson = "{}".toByteArray(),
                enemySpriteIds = emptySet(),
                source = "fixture",
                attribution = "fixture",
                license = "fixture",
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    private val EOC_MOON_CHAPTERS = setOf(
        "Empire of Cats Ch.1",
        "Empire of Cats Ch.2",
        "Empire of Cats Ch.3",
    )

    private fun parse(root: Path, spriteIds: Set<Int>): StageCatalog = StageCatalogParser.parse(
        stageTsv = Files.readAllBytes(root.resolve("stages/stage.tsv")),
        translationTsv = Files.readAllBytes(root.resolve("stages/tstage.tsv")),
        schemeJson = Files.readAllBytes(root.resolve("stages/stage_scheme.json")),
        enemySpriteIds = spriteIds,
        source = "fixture source",
        attribution = "fixture attribution",
        license = "CC BY-SA 4.0",
        towerLabelsJson = Files.readAllBytes(root.resolve("stages/tower_labels.json")),
        chapterLabelsJson = Files.readAllBytes(root.resolve("stages/chapter_labels.json")),
    )

    private fun assetsRoot(): Path {
        val cwd = Path.of(System.getProperty("user.dir"))
        val candidates = listOf(cwd.resolve("app/src/main/assets"), cwd.resolve("../app/src/main/assets").normalize())
        return candidates.firstOrNull { Files.isDirectory(it.resolve("stages")) }
            ?: error("Could not locate app/src/main/assets from $cwd")
    }
}
