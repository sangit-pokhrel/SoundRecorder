package com.vocalis.recorder

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocalis.recorder.audio.AacExporter
import com.vocalis.recorder.audio.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Runs on a real device: the phone's own AAC encoder and MP4 muxer must produce a valid file. */
@RunWith(AndroidJUnit4::class)
class AacExporterTest {
    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun exportsPlayableM4aWithMatchingDuration() {
        val wav = File(dir, "export-test.wav")
        val m4a = File(dir, "export-test.m4a")
        val sampleRate = 48_000
        val seconds = 7
        val tone = FloatArray(sampleRate * seconds) { (0.3 * sin(2 * PI * 440 * it / sampleRate)).toFloat() }
        WavWriter(wav, sampleRate, 1).use { it.write(tone, 0, tone.size) }

        var lastProgress = 0f
        AacExporter.export(wav, m4a) { lastProgress = it }

        assertEquals(1f, lastProgress, 0.01f)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(m4a.path)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
            assertEquals(sampleRate, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            assertTrue("duration ${durationUs}us", abs(durationUs - seconds * 1_000_000L) < 100_000)

            // Timestamps must be strictly increasing (the bug MPEG4Writer rejected before).
            extractor.selectTrack(0)
            var last = -1L
            var frames = 0
            while (extractor.sampleTime >= 0) {
                assertTrue(extractor.sampleTime > last)
                last = extractor.sampleTime
                frames++
                extractor.advance()
            }
            assertTrue(frames > 300)
        } finally {
            extractor.release()
            wav.delete()
            m4a.delete()
        }
    }
}
