package com.vocalis.recorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingNamesTest {

    @Test
    fun defaultNamesCountUpPastTakenOnes() {
        assertEquals("Vocalis 1", RecordingNames.nextDefault { false })
        val taken = setOf("Vocalis 1", "Vocalis 2", "Vocalis 4")
        assertEquals("Vocalis 3", RecordingNames.nextDefault { it in taken })
    }

    @Test
    fun userNamesAlwaysGetThePrefixOnce() {
        assertEquals("Vocalis Interview", RecordingNames.fromUserInput("Interview"))
        assertEquals("Vocalis Interview", RecordingNames.fromUserInput("  Vocalis Interview "))
        assertEquals("vocalis Interview", RecordingNames.fromUserInput("vocalis Interview"))
        assertEquals("Vocalis Vocalist demo", RecordingNames.fromUserInput("Vocalist demo"))
    }

    @Test
    fun userNamesAreMadeFileSafe() {
        assertEquals("Vocalis Q_A part 1_2", RecordingNames.fromUserInput("Q?A   part 1/2"))
        assertNull(RecordingNames.fromUserInput("   "))
    }

    @Test
    fun editablePartStripsOnlyTheFixedPrefix() {
        assertEquals("Interview", RecordingNames.editablePart("Vocalis Interview"))
        assertEquals("", RecordingNames.editablePart("Vocalis"))
        assertEquals("Recording 1", RecordingNames.editablePart("Recording 1"))
        assertEquals("Vocalist demo", RecordingNames.editablePart("Vocalist demo"))
    }
}
