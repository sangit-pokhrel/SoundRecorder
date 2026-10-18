package com.vocalis.recorder.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.vocalis.recorder.Graph
import com.vocalis.recorder.MainActivity
import com.vocalis.recorder.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground service that owns the [AudioEngine], so recording continues with the screen off or
 * the app in the background.
 */
class RecorderService : LifecycleService() {

    private var engine: AudioEngine? = null
    private var outputFile: File? = null
    private var originalFile: File? = null
    private val markers = mutableListOf<Long>()
    private val waveform = ArrayDeque<Float>()
    private var settingsJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_MARK -> addMarker()
            ACTION_RELEARN -> engine?.relearnNoise()
            ACTION_STOP -> finish()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (engine != null) return
        val startedAt = SystemClock.elapsedRealtime()
        // The microphone service type exists from Android 11; Android 10 needs no type.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(paused = false, elapsedMs = 0), type)

        val settings = Graph.settings
        val config = settings.config.value
        val effects = settings.effects.value
        val (out, orig) = Graph.recordings.newRecordingFiles(config.keepOriginal)
        outputFile = out
        originalFile = orig
        markers.clear()
        waveform.clear()

        val newEngine = AudioEngine(
            config = config,
            effects = effects,
            output = out,
            original = orig,
            onLevels = ::onLevels,
            onError = { msg -> lifecycleScope.launch { fail(msg) } },
        )
        try {
            newEngine.start()
        } catch (e: Exception) {
            out.delete()
            orig?.delete()
            fail(e.message ?: "Couldn't start recording")
            return
        }
        engine = newEngine
        Log.i(TAG, "Mic ready in ${SystemClock.elapsedRealtime() - startedAt} ms")
        RecorderController.update {
            RecorderState(status = RecorderStatus.RECORDING, lastFile = it.lastFile, learningNoise = effects.noiseReductionEnabled)
        }
        settingsJob = lifecycleScope.launch {
            settings.effects.collect { newEngine.updateEffects(it) }
        }
    }

    private fun onLevels(r: LevelReport) {
        val point = r.outputPeak.coerceIn(0f, 1f)
        synchronized(waveform) {
            if (engine?.paused != true) {
                waveform.addLast(point)
                while (waveform.size > WAVEFORM_POINTS) waveform.removeFirst()
            }
            val snapshot = waveform.toList()
            val sampleRate = Graph.settings.config.value.sampleRate
            RecorderController.update {
                if (it.status == RecorderStatus.FINISHING || it.status == RecorderStatus.IDLE) it
                else it.copy(
                    elapsedMs = r.framesRecorded * 1000 / sampleRate,
                    peakDb = r.inputPeakDb,
                    rmsDb = r.outputRmsDb,
                    clipped = r.clipped,
                    waveform = snapshot,
                    noiseReductionDb = r.noiseReductionDb,
                    compressionDb = r.compressionDb,
                    breathsSoftened = r.breathsSoftened,
                    learningNoise = r.learningNoise,
                )
            }
        }
    }

    private fun setPaused(paused: Boolean) {
        val e = engine ?: return
        e.paused = paused
        RecorderController.update {
            it.copy(status = if (paused) RecorderStatus.PAUSED else RecorderStatus.RECORDING)
        }
        val elapsed = RecorderController.state.value.elapsedMs
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(paused, elapsed))
    }

    private fun addMarker() {
        if (engine == null) return
        val at = RecorderController.state.value.elapsedMs
        markers += at
        RecorderController.update { it.copy(markersMs = markers.toList()) }
    }

    private fun finish() {
        val e = engine ?: run { stopSelf(); return }
        engine = null
        settingsJob?.cancel()
        RecorderController.update { it.copy(status = RecorderStatus.FINISHING, finishingProgress = 0f) }
        lifecycleScope.launch {
            val out = outputFile
            val effects = Graph.settings.effects.value
            val result = withContext(Dispatchers.IO) {
                e.stop()
                if (out == null || e.framesRecorded == 0L) {
                    out?.delete()
                    originalFile?.delete()
                    return@withContext null
                }
                Graph.recordings.saveMarkers(out, markers)
                if (effects.normalizeEnabled) {
                    runCatching {
                        OfflineProcessor.normalize(out, effects.normalizeTargetLufs, effects.limiterCeilingDb) { p ->
                            RecorderController.update { it.copy(finishingProgress = p) }
                        }
                    }
                }
                out
            }
            Graph.recordings.refresh()
            RecorderController.update { RecorderState(lastFile = result ?: it.lastFile, awaitingName = result != null) }
            ServiceCompat.stopForeground(this@RecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(message: String) {
        engine?.stop()
        engine = null
        settingsJob?.cancel()
        RecorderController.update { RecorderState(lastFile = it.lastFile, error = message) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        super.onDestroy()
    }

    private fun buildNotification(paused: Boolean, elapsedMs: Long): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(icon: Int, label: String, action: String) = NotificationCompat.Action(
            icon, label,
            PendingIntent.getService(this, action.hashCode(), intent(this, action), PendingIntent.FLAG_IMMUTABLE),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(if (paused) R.string.notification_paused else R.string.notification_recording))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setUsesChronometer(!paused)
            .setWhen(System.currentTimeMillis() - elapsedMs)
            .setShowWhen(!paused)
            .addAction(
                if (paused) action(R.drawable.ic_mic, getString(R.string.resume), ACTION_RESUME)
                else action(R.drawable.ic_pause, getString(R.string.pause), ACTION_PAUSE),
            )
            .addAction(action(R.drawable.ic_stop, getString(R.string.stop), ACTION_STOP))
            .build()
    }

    companion object {
        const val ACTION_START = "com.vocalis.recorder.START"
        const val ACTION_PAUSE = "com.vocalis.recorder.PAUSE"
        const val ACTION_RESUME = "com.vocalis.recorder.RESUME"
        const val ACTION_STOP = "com.vocalis.recorder.STOP"
        const val ACTION_MARK = "com.vocalis.recorder.MARK"
        const val ACTION_RELEARN = "com.vocalis.recorder.RELEARN"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val WAVEFORM_POINTS = 140
        private const val TAG = "RecorderService"

        fun intent(context: Context, action: String): Intent =
            Intent(context, RecorderService::class.java).setAction(action)

        fun send(context: Context, action: String) {
            val i = intent(context, action)
            if (action == ACTION_START) ContextCompat.startForegroundService(context, i) else context.startService(i)
        }
    }
}
