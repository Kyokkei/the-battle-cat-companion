package com.projectnyanko.companion.domain.stage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guards the generated enemy table. The stats are decoded from bitmasks and encoded strings, so a
 * silent decoding regression would look plausible on screen — these cases pin the ones that were
 * verified against unmistakable enemy families while the table was built.
 */
class EnemyStatsTableTest {
    private val table: Map<Int, EnemyStats> by lazy {
        val bytes = Files.readAllBytes(assetPath())
        val enemies = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("enemies")
        buildMap {
            for (index in 0 until enemies.length()) {
                val entry = enemies.getJSONObject(index)
                val traits = entry.getJSONArray("traits").let { array ->
                    List(array.length()) { array.getString(it) }
                }
                val abilities = entry.getJSONArray("abilities").let { array ->
                    List(array.length()) { array.getString(it) }
                }
                put(
                    entry.getInt("id"),
                    EnemyStats(
                        id = entry.getInt("id"),
                        name = entry.getString("name"),
                        traits = traits,
                        isArea = entry.getBoolean("isArea"),
                        range = entry.getInt("range"),
                        speed = entry.getInt("speed"),
                        knockbacks = entry.getInt("kb"),
                        abilities = abilities,
                    ),
                )
            }
        }
    }

    @Test
    fun everyEnemyInTheTableHasANameAndAnId() {
        assertTrue("table is suspiciously small: ${table.size}", table.size >= 780)
        table.forEach { (id, stats) ->
            assertEquals(id, stats.id)
            assertTrue("enemy $id has no name", stats.name.isNotBlank())
        }
        // Ids 19, 20 and 747 are empty rows in the source table (no JP, TW or fandom name), so the
        // table falls back to "Enemy <id>" for them rather than inventing one.
        val unnamed = table.values.filter { it.name == "Enemy ${it.id}" }.map { it.id }
        assertTrue("unexpected unnamed enemies: $unnamed", unnamed.size <= 3)
    }

    @Test
    fun traitBitmaskDecodesAgainstKnownEnemies() {
        assertEquals(listOf("Traitless"), table.getValue(0).traits) // Doge
        assertEquals(listOf("Red"), table.getValue(4).traits) // Pigge
        assertEquals(listOf("Metal"), table.getValue(47).traits) // Metal Hippoe
        assertEquals(listOf("Zombie"), table.getValue(284).traits) // Zoge
    }

    @Test
    fun attackTypeSeparatesAreaFromSingleTarget() {
        assertFalse(table.getValue(0).isArea) // Doge hits one cat
        assertTrue(table.getValue(4).isArea) // Pigge sweeps
        assertTrue(table.getValue(47).isArea) // Metal Hippoe sweeps
    }

    @Test
    fun plainStatsMatchTheSourceTable() {
        val doge = table.getValue(0)
        assertEquals(110, doge.range)
        assertEquals(5, doge.speed)
        assertEquals(3, doge.knockbacks)
        assertTrue(doge.abilities.isEmpty())
    }

    @Test
    fun abilityStringsDecodeIntoNamedAbilities() {
        // Zombies burrow and revive; these two ids were confirmed against the whole zombie family.
        val zoge = table.getValue(284).abilities
        assertTrue("Zoge should burrow", zoge.contains("Burrow"))
        assertTrue("Zoge should revive", zoge.contains("Revive"))

        // Relic enemies curse, alien barrier-carriers carry a barrier, and Crazed Axe Cat freezes.
        assertEquals(listOf("Curse"), table.getValue(406).abilities) // Relic Bun-Bun
        assertEquals(listOf("Barrier"), table.getValue(362).abilities) // General GreGory
        assertTrue(table.getValue(77).abilities.contains("Freeze")) // Crazed Axe Cat
    }

    private fun assetPath(): Path {
        val cwd = Path.of(System.getProperty("user.dir"))
        val candidates = listOf(
            cwd.resolve("app/src/main/assets"),
            cwd.resolve("../app/src/main/assets").normalize(),
        )
        val root = candidates.firstOrNull { Files.isDirectory(it.resolve("neko")) }
            ?: error("Could not locate app/src/main/assets from $cwd")
        return root.resolve("neko/data/en/enemy_stats.json")
    }
}
