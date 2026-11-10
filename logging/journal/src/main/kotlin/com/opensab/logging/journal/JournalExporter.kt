package com.opensab.logging.journal

import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packs journal files into a zip for sharing, and prunes the ones a finished share no longer needs. JVM pure. */
object JournalExporter {

    private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

    /** Each entry keeps [files]' path relative to [root]: `sessions/<name>.log`, `days/<date>.log`. */
    fun zip(root: File, files: List<File>, out: File) {
        out.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Deletes zips older than [maxAge]; a share started earlier may still be reading a newer one. */
    fun purge(dir: File, now: Instant, maxAge: Duration = Duration.ofHours(1)) {
        dir.listFiles { file -> file.isFile && file.name.endsWith(".zip") }.orEmpty().forEach { zip ->
            val age = Duration.between(Instant.ofEpochMilli(zip.lastModified()), now)
            if (age > maxAge) zip.delete()
        }
    }

    fun zipName(now: Instant, zone: ZoneId): String = "opensab-sessions-${NAME_FORMAT.format(now.atZone(zone))}.zip"

    /** [zipName], or `-2`, `-3`… when a share started earlier is still reading that name in [dir]. */
    fun freeZipFile(dir: File, now: Instant, zone: ZoneId): File {
        val base = zipName(now, zone).removeSuffix(".zip")
        var file = File(dir, "$base.zip")
        var suffix = 2
        while (file.exists()) {
            file = File(dir, "$base-$suffix.zip")
            suffix++
        }
        return file
    }
}
