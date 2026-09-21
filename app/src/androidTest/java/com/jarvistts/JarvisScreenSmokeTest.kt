package com.jarvistts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises [JarvisScreen] directly against a hand-built [JarvisUiState],
 *  bypassing [MainActivity] so this stays fast and doesn't touch the native
 *  STT/LLM/TTS pipeline. First instrumented coverage for this screen (see
 *  AGENTS.md's TODO list); a smoke test on the two flows most likely to
 *  regress silently -- tap-to-talk and the history drawer -- not full
 *  coverage.
 */
@RunWith(AndroidJUnit4::class)
class JarvisScreenSmokeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun idleState() =
        JarvisUiState().apply {
            phase = Phase.IDLE
            caption = "Tap to talk"
        }

    @Test
    fun tappingIdleMicRing_invokesOnMicTap() {
        var tapped = false
        composeTestRule.setContent {
            JarvisTheme {
                JarvisScreen(state = idleState(), micEnabled = true, onMicTap = { tapped = true })
            }
        }

        composeTestRule.onNodeWithContentDescription("Tap to talk").performClick()

        assert(tapped) { "onMicTap was not invoked by tapping the idle mic ring" }
    }

    @Test
    fun tappingMenuButton_opensHistoryDrawerWithNewConversation() {
        composeTestRule.setContent {
            JarvisTheme {
                JarvisScreen(state = idleState(), micEnabled = true, onMicTap = {})
            }
        }

        composeTestRule.onNodeWithContentDescription("Conversation history").performClick()

        composeTestRule.onNodeWithText("New conversation").assertIsDisplayed()
    }
}
