package com.projectnyanko.companion.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.projectnyanko.companion.MainActivity
import org.junit.Rule
import org.junit.Test

class SeedTrackerScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun seedTrackerLandingScreenRenders() {
        composeRule.onNodeWithText("Trackseed").performClick()
        composeRule.onNodeWithText("Seed tracker").assertIsDisplayed()
        composeRule.onNodeWithText("Find my seed").assertIsDisplayed()
        composeRule.onNodeWithText("I already know my seed").assertIsDisplayed()
    }
}
