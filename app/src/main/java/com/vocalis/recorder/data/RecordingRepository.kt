package com.vocalis.recorder.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.vocalis.recorder.audio.WavReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class Recording(
    val file: File,
    val durationMs: Long,
    val sizeBytes: Long,
    val modifiedAt: Long,
    /** The unprocessed take, if it was kept. Enables A/B comparison and re-processing. */
    val original: File?,
    val markersMs: List<Long>,
    val sampleRate: Int?,
    val channels: Int?,
) {
    val name: String get() = file.nameWithoutExtension
    val isWav: Boolean get() = file.extension.equals("wav", ignoreCase = true)
    val formatLabel: String
        get() = buildString {
            append(file.extension.uppercase())
            if (sampleRate != null) append(" · ${sampleRate / 1000f}kHz".replace(".0kHz", "kHz"))
            if (channels != null) append(if (channels == 1) " · Mono" else " · Stereo")
        }
}

/**
 * Recordings live in the app's external files directory (no storage permission needed and
 * visible over USB under Android/data). Unprocessed takes are kept in `originals/` with the
 * same file name.
 */
class RecordingRepository(private val context: Context) {
    val directory: File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Recordings").apply { mkdirs() }
    private val originals = File(directory, "originals").apply { mkdirs() }

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _recordings.value = directory.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("wav", "m4a") && !f.name.startsWith(".") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map(::load)
    }

    private fun load(file: File): Recording {
        var duration = 0L
        var sampleRate: Int? = null
        var channels: Int? = null
        if (file.extension.equals("wav", true)) {
            runCatching {
                WavReader(file).use {
                    duration = it.durationMs
                    sampleRate = it.sampleRate
                    channels = it.channels
                }
            }
        } else {
            runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(file.path)
                    duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                } finally {
                    mmr.release()
                }
            }
        }
        return Recording(
            file = file,
            durationMs = duration,
            sizeBytes = file.length(),
            modifiedAt = file.lastModified(),
            original = originalFor(file),
            markersMs = readMarkers(file),
            sampleRate = sampleRate,
            channels = channels,
        )
    }

    fun newRecordingFiles(keepOriginal: Boolean): Pair<File, File?> {
        val name = RecordingNames.nextDefault { candidate ->
            File(directory, "$candidate.wav").exists() || File(directory, "$candidate.m4a").exists()
        }
        val out = File(directory, "$name.wav")
        return out to if (keepOriginal) File(originals, out.name) else null
    }

    fun uniqueFile(baseName: String, extension: String): File {
        var candidate = File(directory, "$baseName.$extension")
        var i = 2
        while (candidate.exists()) candidate = File(directory, "$baseName ($i).$extension").also { i++ }
        return candidate
    }

    private fun originalFor(file: File): File? = File(originals, file.name).takeIf { it.exists() }

    private fun markersFile(file: File) = File(directory, ".${file.nameWithoutExtension}.markers")

    fun saveMarkers(file: File, markersMs: List<Long>) {
        val f = markersFile(file)
        if (markersMs.isEmpty()) f.delete() else f.writeText(markersMs.joinToString("\n"))
    }

    private fun readMarkers(file: File): List<Long> = runCatching {
        markersFile(file).takeIf { it.exists() }?.readLines()?.mapNotNull { it.trim().toLongOrNull() }
    }.getOrNull().orEmpty()

    suspend fun rename(recording: Recording, newName: String): Boolean = withContext(Dispatchers.IO) {
        val clean = RecordingNames.fromUserInput(newName) ?: return@withContext false
        val target = File(directory, "$clean.${recording.file.extension}")
        if (target == recording.file) return@withContext true
        if (target.exists()) return@withContext false
        val markers = markersFile(recording.file)
        if (!recording.file.renameTo(target)) return@withContext false
        recording.original?.renameTo(File(originals, target.name))
        if (markers.exists()) markers.renameTo(markersFile(target))
        refresh()
        true
    }

    suspend fun delete(recording: Recording) = withContext(Dispatchers.IO) {
        recording.file.delete()
        recording.original?.delete()
        markersFile(recording.file).delete()
        refresh()
    }

    /** Copies a recording into the shared Music/Vocalis folder so other apps can see it. */
    suspend fun exportToMusicLibrary(file: File): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = if (file.extension.equals("m4a", true)) "audio/mp4" else "audio/wav"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Vocalis")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return@withContext null
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    fun shareUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
