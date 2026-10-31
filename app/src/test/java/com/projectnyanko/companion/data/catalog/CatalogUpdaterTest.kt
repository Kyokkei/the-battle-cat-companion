package com.projectnyanko.companion.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refresh guard rails, tested without a network.
 *
 * They matter because the failure is silent: a bad refresh shows yesterday's banners and quietly
 * hides the guaranteed track, which looks like a tracker bug rather than a data one.
 */
class CatalogUpdaterTest {
    @Test
    fun aParsedCatalogCountsItsEvents() {
        val json = """[{"event_id":"a"},{"event_id":"b"},{"event_id":"c"}]""".toByteArray()
        assertEquals(3, CatalogRules.countEvents(json))
    }

    @Test
    fun aPoolTableIsAnObjectNotAnArray() {
        // `gacha_pools.json` is keyed by pool id. Counting it as an array returned null, so every
        // pool download was thrown away while the event list refreshed — a half-updated catalog.
        val pools = """{"215":{"cats":[1,2]},"221":{"cats":[3]}}""".toByteArray()
        assertEquals(2, CatalogRules.countEvents(pools))
    }

    @Test
    fun anHtmlErrorPageIsNotACatalog() {
        assertNull(CatalogRules.countEvents("<html>nope</html>".toByteArray()))
        assertNull(CatalogRules.countEvents(ByteArray(0)))
        assertNull(CatalogRules.countEvents(null))
    }

    @Test
    fun aJsonErrorBodyIsCountedButStillRefused() {
        // `{"message":"404 File Not Found"}` is a valid JSON object with one key, so the counter
        // cannot tell it apart from a one-pool table. The count comparison is what saves us: one
        // entry never beats the hundreds already on disk.
        val error = """{"message":"404 File Not Found"}""".toByteArray()
        assertEquals(1, CatalogRules.countEvents(error))
        assertFalse(CatalogRules.shouldReplace(current = 585, incoming = CatalogRules.countEvents(error)))
    }

    @Test
    fun aBiggerCatalogReplacesTheOldOne() {
        assertTrue(CatalogRules.shouldReplace(current = 2161, incoming = 2167))
    }

    @Test
    fun anIdenticalCatalogIsHarmless() {
        assertTrue(CatalogRules.shouldReplace(current = 2161, incoming = 2161))
    }

    @Test
    fun aSmallerCatalogIsRefused() {
        // The whole point: an upstream hiccup must not empty the app's banner list.
        assertFalse(CatalogRules.shouldReplace(current = 2161, incoming = 12))
        assertFalse(CatalogRules.shouldReplace(current = 2161, incoming = 2160))
    }

    @Test
    fun nothingParsesSoNothingIsWritten() {
        assertFalse(CatalogRules.shouldReplace(current = 2161, incoming = null))
        assertFalse(CatalogRules.shouldReplace(current = null, incoming = 0))
    }

    @Test
    fun theFirstDownloadIsAlwaysAllowed() {
        assertTrue(CatalogRules.shouldReplace(current = null, incoming = 2167))
    }
}
