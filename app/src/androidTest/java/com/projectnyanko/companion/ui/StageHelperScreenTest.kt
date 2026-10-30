package com.projectnyanko.companion.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.projectnyanko.companion.MainActivity
import org.junit.Rule
import org.junit.Test

class StageHelperScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun stageHelperShowsLocalCatalogAndExplicitCaptureFlow() {
        composeRule.onNodeWithText("Stage helper").performClick()
        composeRule.onNodeWithText("Start the helper before opening Battle Cats.", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("BCEN").assertIsDisplayed()
        composeRule.onNodeWithText("BCJP").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("6328 stages", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Battle Cats Wiki contributors", substring = true).assertIsDisplayed()
    }
}
