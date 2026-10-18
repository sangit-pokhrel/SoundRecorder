package com.vocalis.recorder

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocalis.recorder.audio.RecorderState
import com.vocalis.recorder.audio.RecorderStatus
import com.vocalis.recorder.data.RecorderConfig
import com.vocalis.recorder.dsp.EffectSettings
import com.vocalis.recorder.dsp.Preset
import com.vocalis.recorder.ui.components.RecordingActions
import com.vocalis.recorder.ui.screens.HomeScreen
import com.vocalis.recorder.ui.screens.RecorderActions
import com.vocalis.recorder.ui.theme.VocalisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()
    private val recorder = RecorderActions(
        onRecord = { events += "record" },
        onStop = { events += "stop" },
        onPause = { events += "pause" },
        onResume = { events += "resume" },
        onMark = { events += "mark" },
        onRelearn = { events += "relearn" },
    )
    private val noActions = RecordingActions({}, {}, {}, {}, {}, { _, _ -> }, {})

    private fun show(state: RecorderState, onPreset: (Preset) -> Unit = {}) {
        compose.setContent {
            VocalisTheme {
                HomeScreen(
                    state = state,
                    effects = EffectSettings(),
                    config = RecorderConfig(),
                    recent = emptyList(),
                    playingFile = null,
                    recorder = recorder,
                    recordingActions = noActions,
                    onPreset = onPreset,
                    onConfig = {},
                    onOpenEffects = {},
                    onSearch = {},
                    onSeeAll = {},
                )
            }
        }
    }

    @Test
    fun idleShowsTapToRecordAndStartsRecording() {
        show(RecorderState())
        compose.onNodeWithText("Tap to Record").assertIsDisplayed()
        compose.onNodeWithText("High quality  •  Noise & Breath cleanup on").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start recording").performClick()
        assertEquals(listOf("record"), events)
    }

    @Test
    fun quickModeAppliesPreset() {
        var chosen: Preset? = null
        show(RecorderState()) { chosen = it }
        compose.onNodeWithText("Meeting").performClick()
        assertEquals(Preset.LECTURE, chosen)
    }

    @Test
    fun recordingShowsLiveControls() {
        show(RecorderState(status = RecorderStatus.RECORDING, elapsedMs = 14_800, breathsSoftened = 3))
        compose.onNodeWithText("00:14.8").assertIsDisplayed()
        compose.onNodeWithText("Breaths").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Stop recording").performClick()
        assertTrue(events.containsAll(listOf("pause", "stop")))
    }
}
