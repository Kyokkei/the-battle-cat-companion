package com.projectnyanko.companion.data.update

import org.junit.Assert.assertFalse
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
    fun nothingHappensUntilAManifestIsConfigured() {
        // The URL is deliberately blank until a release manifest exists.
        assertFalse(UpdateChecker.isConfigured())
    }
}
