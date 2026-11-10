package com.opensab.logging.journal

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object SessionFileNames {

    private val PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    /** `2026-09-24_20-24-13_s1`: local time of the opening, and the process-local session number. */
    fun baseName(local: LocalDateTime, number: Int): String = "${PATTERN.format(local)}_s$number"
}
