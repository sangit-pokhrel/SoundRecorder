package com.vocalis.recorder.data

/**
 * Naming rules for recordings. Every file Vocalis creates starts with "Vocalis" so it is easy to
 * spot among other audio on the phone, in the Music folder or in a share sheet.
 */
object RecordingNames {
    const val PREFIX = "Vocalis"

    /** The next free default name: "Vocalis 1", "Vocalis 2", ... */
    fun nextDefault(taken: (String) -> Boolean): String {
        var n = 1
        while (taken("$PREFIX $n")) n++
        return "$PREFIX $n"
    }

    /** The user-editable part of a name: everything after the fixed "Vocalis " prefix. */
    fun editablePart(name: String): String {
        val trimmed = name.trim()
        return when {
            trimmed.equals(PREFIX, ignoreCase = true) -> ""
            trimmed.startsWith("$PREFIX ", ignoreCase = true) -> trimmed.substring(PREFIX.length + 1).trim()
            else -> trimmed
        }
    }

    /**
     * Cleans a user-entered name for use as a file name and adds the "Vocalis" prefix unless
     * it is already there. Returns null if nothing usable is left.
     */
    fun fromUserInput(input: String): String? {
        val clean = input.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return null
        val alreadyPrefixed = clean.equals(PREFIX, ignoreCase = true) ||
            clean.startsWith("$PREFIX ", ignoreCase = true)
        return if (alreadyPrefixed) clean else "$PREFIX $clean"
    }
}
