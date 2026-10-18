package com.vocalis.recorder.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import com.vocalis.recorder.data.RecorderConfig
import com.vocalis.recorder.dsp.EffectSettings
import com.vocalis.recorder.dsp.ProcessingChain
import com.vocalis.recorder.dsp.SpectralDenoiser
import com.vocalis.recorder.dsp.linearToDb
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Snapshot of the live signal, delivered ~20 times per second from the audio thread. */
data class LevelReport(
    val framesRecorded: Long,
    val inputPeakDb: Float,
    val outputRmsDb: Float,
    val outputPeak: Float,
    val clipped: Boolean,
    val noiseReductionDb: Float,
    val compressionDb: Float,
    val breathsSoftened: Int,
    val learningNoise: Boolean,
)

/**
 * Captures the microphone with [AudioRecord], runs the [ProcessingChain] in real time and streams
 * the result to a WAV file (plus an untouched copy when [original] is set, so the user can
 * always go back or re-process later). Optional live monitoring plays the processed signal.
 */
class AudioEngine(
    private val config: RecorderConfig,
    effects: EffectSettings,
    private val output: File,
    private val original: File?,
    private val onLevels: (LevelReport) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val sampleRate = config.sampleRate
    private val channels = if (config.stereo) 2 else 1
    private val chain = ProcessingChain(sampleRate, channels, effects)
    private val pendingEffects = AtomicReference<EffectSettings?>(null)

    @Volatile private var running = false
    @Volatile var paused = false
    // The denoiser learns the room automatically during the first moment of every recording.
    @Volatile private var relearnUntilFrame =
        if (effects.noiseReductionEnabled) (SpectralDenoiser.LEARN_SECONDS * sampleRate).toLong() else -1L

    @Volatile var framesRecorded = 0L
        private set

    private var record: AudioRecord? = null
    private var monitor: AudioTrack? = null
    private val deviceEffects = mutableListOf<AudioEffect>()
    private var thread: Thread? = null
    private var writer: WavWriter? = null
    private var originalWriter: WavWriter? = null
    private var framesProcessed = 0L

    /** Labels of device effects that were actually enabled, for the UI. */
    val activeDeviceEffects = mutableListOf<String>()

    @SuppressLint("MissingPermission") // Caller checks RECORD_AUDIO before starting the service.
    fun start() {
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "This device can't record at $sampleRate Hz" }
        val bufferBytes = max(minBuffer * 4, sampleRate * channels * 2 / 5)

        fun open(source: Int): AudioRecord? = runCatching {
            AudioRecord(source, sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull()

        val rec = open(config.micSource.source) ?: open(MediaRecorder.AudioSource.MIC)
            ?: throw IllegalStateException("Microphone unavailable")
        record = rec
        attachDeviceEffects(rec.audioSessionId, chain.settings)

        writer = WavWriter(output, sampleRate, channels)
        originalWriter = original?.let { WavWriter(it, sampleRate, channels) }
        if (config.liveMonitor) monitor = openMonitor()

        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            release()
            throw IllegalStateException("The microphone is being used by another app")
        }
        monitor?.play()
        running = true
        thread = Thread(::loop, "VocalisAudio").apply { start() }
    }

    fun updateEffects(settings: EffectSettings) {
        pendingEffects.set(settings)
    }

    /** Re-learn the background noise from the next moment of audio. Ask the user to stay quiet. */
    fun relearnNoise() {
        chain.relearnNoise()
        relearnUntilFrame = framesProcessed + (SpectralDenoiser.LEARN_SECONDS * sampleRate).toLong()
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
        release()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rec = record ?: return
        val blockFrames = sampleRate / 50
        val shorts = ShortArray(blockFrames * channels)
        val samples = FloatArray(blockFrames * channels)
        val reportEvery = sampleRate / 20
        var sinceReport = 0
        var inPeak = 0f
        var outPeak = 0f
        var outSquares = 0.0
        var squareCount = 0
        var clipped = false

        try {
            while (running) {
                val read = rec.read(shorts, 0, shorts.size)
                if (read < 0) {
                    onError("Microphone error ($read)")
                    break
                }
                if (read == 0) continue
                val frames = read / channels
                val count = frames * channels

                for (i in 0 until count) {
                    val v = shorts[i] / 32768f
                    samples[i] = v
                    val a = abs(v)
                    if (a > inPeak) inPeak = a
                    if (a >= 0.999f) clipped = true
                }

                pendingEffects.getAndSet(null)?.let { chain.configure(it) }

                val isPaused = paused
                if (!isPaused) originalWriter?.write(samples, 0, count)
                chain.process(samples, frames)
                framesProcessed += frames
                if (!isPaused) {
                    writer?.write(samples, 0, count)
                    framesRecorded += frames
                }

                monitor?.let { track ->
                    for (i in 0 until count) shorts[i] = (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                    track.write(shorts, 0, count, AudioTrack.WRITE_NON_BLOCKING)
                }

                for (i in 0 until count) {
                    val a = abs(samples[i])
                    if (a > outPeak) outPeak = a
                    outSquares += samples[i].toDouble() * samples[i]
                }
                squareCount += count
                sinceReport += frames
                if (sinceReport >= reportEvery) {
                    onLevels(
                        LevelReport(
                            framesRecorded = framesRecorded,
                            inputPeakDb = linearToDb(inPeak),
                            outputRmsDb = linearToDb(sqrt(outSquares / max(squareCount, 1)).toFloat()),
                            outputPeak = outPeak,
                            clipped = clipped,
                            noiseReductionDb = chain.noiseReductionDb,
                            compressionDb = chain.compressorGainReductionDb,
                            breathsSoftened = chain.breathsDetected,
                            learningNoise = framesProcessed < relearnUntilFrame,
                        ),
                    )
                    sinceReport = 0
                    inPeak = 0f
                    outPeak = 0f
                    outSquares = 0.0
                    squareCount = 0
                    clipped = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio loop failed", e)
            onError(e.message ?: "Recording failed")
        }
    }

    private fun openMonitor(): AudioTrack? = runCatching {
        val outMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(sampleRate, outMask, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(outMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }.getOrNull()

    private fun attachDeviceEffects(sessionId: Int, s: EffectSettings) {
        fun add(label: String, available: Boolean, create: () -> AudioEffect?) {
            if (!available) return
            runCatching { create() }.getOrNull()?.let {
                it.enabled = true
                deviceEffects += it
                activeDeviceEffects += label
            }
        }
        if (s.hwNoiseSuppressor) add("Device noise suppressor", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sessionId) }
        if (s.hwEchoCanceler) add("Echo canceller", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(sessionId) }
        if (s.hwAutoGain) add("Auto gain", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(sessionId) }
    }

    private fun release() {
        runCatching { record?.stop() }
        record?.release()
        record = null
        deviceEffects.forEach { runCatching { it.release() } }
        deviceEffects.clear()
        monitor?.let { runCatching { it.stop() }; it.release() }
        monitor = null
        runCatching { writer?.close() }
        runCatching { originalWriter?.close() }
        writer = null
        originalWriter = null
    }

    private companion object {
        const val TAG = "AudioEngine"
    }
}
