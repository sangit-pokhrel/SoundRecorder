package com.vocalis.recorder.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

enum class RecorderStatus { IDLE, RECORDING, PAUSED, FINISHING }

data class RecorderState(
    val status: RecorderStatus = RecorderStatus.IDLE,
    val elapsedMs: Long = 0,
    val peakDb: Float = -90f,
    val rmsDb: Float = -90f,
    val clipped: Boolean = false,
    /** Recent levels in 0..1, oldest first, for the scrolling live waveform. */
    val waveform: List<Float> = emptyList(),
    val noiseReductionDb: Float = 0f,
    val compressionDb: Float = 0f,
    val breathsSoftened: Int = 0,
    val markersMs: List<Long> = emptyList(),
    val finishingProgress: Float = 0f,
    val learningNoise: Boolean = false,
    val lastFile: File? = null,
    val error: String? = null,
)

/** Shared state between the recording service and the UI. */
object RecorderController {
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    internal fun update(transform: (RecorderState) -> RecorderState) = _state.update(transform)

    fun clearError() = _state.update { it.copy(error = null) }
}
