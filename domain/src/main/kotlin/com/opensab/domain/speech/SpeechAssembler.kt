package com.opensab.domain.speech

/** Joins the essential part, the pause and the handlebar help without doubling punctuation. */
fun assembleSpeechText(essential: String, pause: String = "", help: String? = null): String {
    val trimmedEssential = essential.trim()
    val trimmedHelp = help?.trim().orEmpty()

    if (trimmedHelp.isEmpty()) return trimmedEssential
    if (trimmedEssential.isEmpty()) return trimmedHelp

    val lastChar = trimmedEssential.last()
    val endsWithPunctuation = lastChar == '.' || lastChar == '?' || lastChar == '!' || lastChar == '…'

    return if (endsWithPunctuation) {
        "$trimmedEssential $trimmedHelp"
    } else {
        val trimmedPause = pause.trim()
        val sep = trimmedPause.ifEmpty { "." }
        if (sep == "." || sep == "," || sep == "…") {
            "$trimmedEssential$sep $trimmedHelp"
        } else {
            "$trimmedEssential $sep $trimmedHelp"
        }
    }
}
