package com.projectnyanko.companion.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version comparison for the update prompt: it must never nag on a build that is already current. */
class UpdateCheckerTest {
    @Test
    fun aHigherVersionIsNewer() {
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.1.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("0.1.10", "0.1.9"))
    }

    @Test
    fun theSameVersionIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun anOlderVersionIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.2.0"))
        assertFalse(UpdateChecker.isNewer("0.1.9", "0.1.10"))
    }

    @Test
    fun nonsenseNeverTriggersAnUpdate() {
        assertFalse(UpdateChecker.isNewer("", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("beta", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.2.0", ""))
    }

    @Test
    fun theCheckerPointsAtThisReposReleases() {
        // The old version needed a hand-hosted manifest, and forgetting to update it meant the app
        // silently stopped telling anyone about new builds. GitHub's own endpoint cannot go stale.
        assertTrue(UpdateChecker.isConfigured())
        assertTrue(UpdateChecker.RELEASES_API.contains("Kyokkei/the-battle-cat-companion"))
    }

    /** A real `releases/latest` payload, trimmed to the fields the app reads. */
    private val release = """
        {
          "tag_name": "v0.1.3",
          "html_url": "https://github.com/Kyokkei/the-battle-cat-companion/releases/tag/v0.1.3",
          "body": "## What changed\n\n---\n\nBanner data now refreshes itself.\nThe 100% column is real.\n"
        }
    """.trimIndent()

    @Test
    fun theTagLosesItsVAndBecomesTheVersion() {
        val info = UpdateChecker.parseRelease(release, currentVersion = "0.1.2")
        assertNotNull(info)
        assertEquals("0.1.3", info!!.version)
    }

    @Test
    fun theReleasePageIsTheDownloadTarget() {
        val info = UpdateChecker.parseRelease(release, currentVersion = "0.1.2")
        assertEquals("https://github.com/Kyokkei/the-battle-cat-companion/releases/tag/v0.1.3", info!!.url)
    }

    @Test
    fun notesLoseTheirMarkdown() {
        val notes = UpdateChecker.parseRelease(release, currentVersion = "0.1.2")!!.notes
        assertNotNull(notes)
        assertFalse("headings should be dropped", notes!!.contains("#"))
        assertFalse("rules should be dropped", notes.contains("---"))
        assertTrue(notes.contains("Banner data now refreshes itself."))
    }

    @Test
    fun beingUpToDateReturnsNothing() {
        // Same version, and an older tag, must both stay quiet.
        assertNull(UpdateChecker.parseRelease(release, currentVersion = "0.1.3"))
        assertNull(UpdateChecker.parseRelease(release, currentVersion = "0.2.0"))
    }

    @Test
    fun aGithubErrorPayloadIsIgnored() {
        val notFound = """{"message":"Not Found","documentation_url":"https://docs.github.com"}"""
        assertNull(UpdateChecker.parseRelease(notFound, currentVersion = "0.1.0"))
        assertNull(UpdateChecker.parseRelease("", currentVersion = "0.1.0"))
    }
}
