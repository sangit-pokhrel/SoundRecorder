package com.vocalis.recorder.ui

import android.app.Application
import android.content.Intent
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vocalis.recorder.Graph
import com.vocalis.recorder.audio.AacExporter
import com.vocalis.recorder.audio.OfflineProcessor
import com.vocalis.recorder.audio.RecorderController
import com.vocalis.recorder.audio.RecorderService
import com.vocalis.recorder.audio.RecorderStatus
import com.vocalis.recorder.audio.WavReader
import com.vocalis.recorder.data.Recording
import com.vocalis.recorder.data.RecordingNames
import com.vocalis.recorder.data.RecorderConfig
import com.vocalis.recorder.dsp.EffectSettings
import com.vocalis.recorder.dsp.Preset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

data class PlayerState(
    val recording: Recording? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playingOriginal: Boolean = false,
    val peaks: FloatArray? = null,
)

/** A long-running file operation shown as a progress card. */
data class TaskState(val label: String, val progress: Float)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    val recorder = RecorderController.state
    val effects: StateFlow<EffectSettings> = Graph.settings.effects
    val config: StateFlow<RecorderConfig> = Graph.settings.config
    val recordings: StateFlow<List<Recording>> = Graph.recordings.recordings
    val storagePath: String = Graph.recordings.directory.absolutePath

    private val _player = MutableStateFlow(PlayerState())
    val player: StateFlow<PlayerState> = _player.asStateFlow()

    private val _task = MutableStateFlow<TaskState?>(null)
    val task: StateFlow<TaskState?> = _task.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var mediaPlayer: MediaPlayer? = null
    private var ticker: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { Graph.recordings.refresh() }
    }

    // ---- Recording ----

    fun startRecording() {
        // The previous take must be named (or discarded) first.
        if (recorder.value.status != RecorderStatus.IDLE || recorder.value.awaitingName) return
        closePlayer()
        RecorderController.markStarting()
        RecorderService.send(context, RecorderService.ACTION_START)
        viewModelScope.launch {
            delay(START_TIMEOUT_MS)
            RecorderController.cancelStartingIfStuck("Couldn't start the microphone. Please try again.")
        }
    }

    fun stopRecording() = RecorderService.send(context, RecorderService.ACTION_STOP)
    fun pauseRecording() = RecorderService.send(context, RecorderService.ACTION_PAUSE)
    fun resumeRecording() = RecorderService.send(context, RecorderService.ACTION_RESUME)
    fun addMarker() = RecorderService.send(context, RecorderService.ACTION_MARK)
    fun relearnNoise() = RecorderService.send(context, RecorderService.ACTION_RELEARN)
    fun clearError() = RecorderController.clearError()

    // ---- Naming a fresh take ----

    private val _nameError = MutableStateFlow<String?>(null)
    val nameError: StateFlow<String?> = _nameError.asStateFlow()

    /** Saves the just-recorded take as "Vocalis <name>". A blank name keeps the default. */
    fun saveRecordingName(typed: String) {
        val file = recorder.value.lastFile ?: return RecorderController.finishNaming()
        viewModelScope.launch {
            val suffix = RecordingNames.editablePart(typed)
            if (suffix.isNotBlank()) {
                // The library may not have picked up the brand-new file yet.
                val rec = Graph.recordings.recordings.value.firstOrNull { it.file == file }
                    ?: Graph.recordings.refresh().let { Graph.recordings.recordings.value.firstOrNull { it.file == file } }
                if (rec != null && !Graph.recordings.rename(rec, "${RecordingNames.PREFIX} $suffix")) {
                    _nameError.value = "A recording with that name already exists"
                    return@launch
                }
            }
            _nameError.value = null
            RecorderController.finishNaming()
        }
    }

    fun discardNewRecording() {
        val file = recorder.value.lastFile
        viewModelScope.launch {
            Graph.recordings.recordings.value.firstOrNull { it.file == file }?.let { Graph.recordings.delete(it) }
            _nameError.value = null
            RecorderController.finishNaming()
        }
    }

    fun clearNameError() {
        _nameError.value = null
    }

    // ---- Settings ----

    fun updateEffects(transform: (EffectSettings) -> EffectSettings) = Graph.settings.updateEffects(transform)

    /** Applies a preset while keeping the user's input gain and device-effect choices. */
    fun applyPreset(preset: Preset) = Graph.settings.updateEffects { current ->
        preset.settings.copy(
            inputGainDb = current.inputGainDb,
            hwNoiseSuppressor = current.hwNoiseSuppressor,
            hwEchoCanceler = current.hwEchoCanceler,
            hwAutoGain = current.hwAutoGain,
        )
    }

    fun updateConfig(transform: (RecorderConfig) -> RecorderConfig) = Graph.settings.updateConfig(transform)

    // ---- Playback ----

    fun open(recording: Recording) {
        closePlayer()
        _player.value = PlayerState(recording = recording, durationMs = recording.durationMs)
        prepare(recording.file, startAt = 0, play = true)
        viewModelScope.launch {
            val peaks = if (recording.isWav) withContext(Dispatchers.IO) { loadPeaks(recording.file) } else null
            _player.update { if (it.recording?.file == recording.file) it.copy(peaks = peaks) else it }
        }
    }

    fun togglePlay() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) mp.pause() else mp.start()
        _player.update { it.copy(playing = mp.isPlaying) }
    }

    fun seekTo(fraction: Float) {
        val mp = mediaPlayer ?: return
        val pos = (fraction * mp.duration).toLong()
        mp.seekTo(pos, MediaPlayer.SEEK_CLOSEST)
        _player.update { it.copy(positionMs = pos) }
    }

    fun skip(deltaMs: Long) {
        val mp = mediaPlayer ?: return
        val pos = (mp.currentPosition + deltaMs).coerceIn(0, mp.duration.toLong())
        mp.seekTo(pos, MediaPlayer.SEEK_CLOSEST)
        _player.update { it.copy(positionMs = pos) }
    }

    /** A/B compare: switch between the processed and the untouched take at the same position. */
    fun setPlayOriginal(original: Boolean) {
        val state = _player.value
        val rec = state.recording ?: return
        val target = if (original) rec.original ?: return else rec.file
        if (state.playingOriginal == original) return
        val wasPlaying = mediaPlayer?.isPlaying == true
        val pos = mediaPlayer?.currentPosition?.toLong() ?: state.positionMs
        _player.update { it.copy(playingOriginal = original) }
        prepare(target, pos, wasPlaying)
    }

    fun closePlayer() {
        ticker?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        _player.value = PlayerState()
    }

    private fun prepare(file: File, startAt: Long, play: Boolean) {
        mediaPlayer?.release()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.path)
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            _messages.tryEmit("Can't play this file")
            return
        }
        mp.setOnCompletionListener { _player.update { s -> s.copy(playing = false, positionMs = s.durationMs) } }
        if (startAt > 0) mp.seekTo(startAt, MediaPlayer.SEEK_CLOSEST)
        if (play) mp.start()
        mediaPlayer = mp
        _player.update { it.copy(playing = play, durationMs = mp.duration.toLong(), positionMs = startAt) }
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                mediaPlayer?.let { p -> if (p.isPlaying) _player.update { it.copy(positionMs = p.currentPosition.toLong()) } }
                delay(40)
            }
        }
    }

    private fun loadPeaks(file: File, buckets: Int = 160): FloatArray? = runCatching {
        WavReader(file).use { r ->
            val peaks = FloatArray(buckets)
            val framesPerBucket = (r.totalFrames / buckets).coerceAtLeast(1)
            val buf = FloatArray(4096 * r.channels)
            var frame = 0L
            while (true) {
                val n = r.read(buf, 4096)
                if (n == 0) break
                for (i in 0 until n * r.channels) {
                    val b = ((frame + i / r.channels) / framesPerBucket).toInt().coerceAtMost(buckets - 1)
                    val a = abs(buf[i])
                    if (a > peaks[b]) peaks[b] = a
                }
                frame += n
            }
            peaks
        }
    }.getOrNull()

    // ---- File actions ----

    /** Re-renders a recording with the current effect settings into a new file. */
    fun enhance(recording: Recording) = runTask("Enhancing") { progress ->
        val source = recording.original ?: recording.file
        val out = Graph.recordings.uniqueFile("${recording.name} (enhanced)", "wav")
        OfflineProcessor.enhance(source, out, effects.value, progress)
        "Saved ${out.nameWithoutExtension}"
    }

    fun exportM4a(recording: Recording) = runTask("Exporting M4A") { progress ->
        val out = Graph.recordings.uniqueFile(recording.name, "m4a")
        AacExporter.export(recording.file, out, onProgress = progress)
        "Exported ${out.name}"
    }

    fun saveToMusicLibrary(recording: Recording) {
        viewModelScope.launch {
            val uri = Graph.recordings.exportToMusicLibrary(recording.file)
            _messages.emit(if (uri != null) "Saved to Music/Vocalis" else "Couldn't save to Music")
        }
    }

    fun shareIntent(recording: Recording): Intent {
        val uri = Graph.recordings.shareUri(recording.file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (recording.isWav) "audio/wav" else "audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, recording.name)
    }

    fun rename(recording: Recording, name: String) {
        viewModelScope.launch {
            if (!Graph.recordings.rename(recording, name)) _messages.emit("A recording with that name already exists")
        }
    }

    fun delete(recording: Recording) {
        if (_player.value.recording?.file == recording.file) closePlayer()
        viewModelScope.launch { Graph.recordings.delete(recording) }
    }

    private fun runTask(label: String, work: (progress: (Float) -> Unit) -> String) {
        if (_task.value != null) {
            _messages.tryEmit("Please wait for the current task to finish")
            return
        }
        _task.value = TaskState(label, 0f)
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching { work { p -> _task.value = TaskState(label, p) } }
                    .getOrElse { "$label failed: ${it.message}" }
            }
            _task.value = null
            Graph.recordings.refresh()
            _messages.emit(message)
        }
    }

    override fun onCleared() {
        closePlayer()
    }

    private companion object {
        const val START_TIMEOUT_MS = 8_000L
    }
}
