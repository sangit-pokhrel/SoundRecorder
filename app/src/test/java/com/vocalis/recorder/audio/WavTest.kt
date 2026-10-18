package com.vocalis.recorder.audio

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class WavTest {
    private val dir: File = Files.createTempDirectory("wavtest").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun roundTripsStereo16Bit() {
        val file = File(dir, "stereo.wav")
        val samples = FloatArray(2000) { if (it % 2 == 0) 0.5f else -0.25f }
        WavWriter(file, 44_100, 2).use { it.write(samples, 0, samples.size) }

        WavReader(file).use { r ->
            assertEquals(44_100, r.sampleRate)
            assertEquals(2, r.channels)
            assertEquals(1000L, r.totalFrames)
            val out = FloatArray(samples.size)
            assertEquals(1000, r.read(out, 1000))
            assertArrayEquals(samples, out, 1f / 32768)
            assertEquals("reads past the end return 0", 0, r.read(out, 10))
        }
    }

    @Test
    fun clampsOutOfRangeSamplesInsteadOfWrapping() {
        val file = File(dir, "clip.wav")
        WavWriter(file, 48_000, 1).use { it.write(floatArrayOf(1.7f, -3f), 0, 2) }
        WavReader(file).use { r ->
            val out = FloatArray(2)
            r.read(out, 2)
            assertEquals(32767f / 32768f, out[0], 1e-6f)
            assertEquals(-32767f / 32768f, out[1], 1e-6f)
        }
    }

    @Test
    fun recordingInterruptedBeforeCloseIsStillReadable() {
        // Simulates a crash: the writer is never closed, so only the periodic header refresh
        // and the reader's tolerance of a stale data size keep the take usable.
        val file = File(dir, "crash.wav")
        val writer = WavWriter(file, 8_000, 1)
        val block = FloatArray(8_000) { 0.1f }
        repeat(5) { writer.write(block, 0, block.size) } // 5 s, past the 2 s header refresh

        WavReader(file).use { r ->
            assertEquals(5 * 8_000L, r.totalFrames)
        }
    }

    @Test
    fun readsFloatAnd24BitFiles() {
        val floatFile = File(dir, "float.wav")
        writeRaw(floatFile, format = 3, bits = 32) { it.putFloat(0.75f).putFloat(-0.5f) }
        WavReader(floatFile).use { r ->
            val out = FloatArray(2)
            assertEquals(2, r.read(out, 2))
            assertArrayEquals(floatArrayOf(0.75f, -0.5f), out, 0f)
        }

        val pcm24 = File(dir, "pcm24.wav")
        writeRaw(pcm24, format = 1, bits = 24) {
            // 0x400000 = +0.5, 0xC00000 = -0.5
            it.put(0).put(0).put(0x40).put(0).put(0).put(0xC0.toByte())
        }
        WavReader(pcm24).use { r ->
            val out = FloatArray(2)
            r.read(out, 2)
            assertArrayEquals(floatArrayOf(0.5f, -0.5f), out, 1e-6f)
        }
    }

    @Test
    fun rejectsFilesThatAreNotWav() {
        val junk = File(dir, "junk.wav").apply { writeText("definitely not audio, just some text padding") }
        val error = runCatching { WavReader(junk) }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    private fun writeRaw(file: File, format: Int, bits: Int, body: (ByteBuffer) -> Unit) {
        val data = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        body(data)
        val dataSize = data.position()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(format.toShort()).putShort(1)
        h.putInt(48_000).putInt(48_000 * bits / 8).putShort((bits / 8).toShort()).putShort(bits.toShort())
        h.put("data".toByteArray()).putInt(dataSize)
        RandomAccessFile(file, "rw").use {
            it.write(h.array())
            it.write(data.array(), 0, dataSize)
        }
    }
}
