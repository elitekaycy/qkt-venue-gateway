package com.qkt.venuegateway.deribit

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

/**
 * One contract's recorded series, kept as a file a UTC day in [dir] (`<yyyy-MM-dd>.csv`, each starting with
 * [header], rows oldest first, each row's first cell its time in milliseconds). A last line without its
 * newline is an append a crash cut short: it is never served, and it is cut off before the next append.
 * Not thread-safe: its recorder calls it under one lock.
 */
internal class DeribitDayFiles(
    private val dir: Path,
    private val header: String,
) {
    private val log = LoggerFactory.getLogger(DeribitDayFiles::class.java)

    /** The day files from [first] to [last], oldest first. */
    fun days(
        first: LocalDate = LocalDate.MIN,
        last: LocalDate = LocalDate.MAX,
    ): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { files ->
            files
                .filter { DAY_FILE.matches(it.fileName.toString()) }
                .filter { dayOf(it) in first..last }
                .sorted()
                .toList()
        }
    }

    /** [file]'s complete rows after its header, each with its line number in the file. */
    fun rows(file: Path): List<IndexedValue<String>> =
        complete(file)
            .lines()
            .withIndex()
            .drop(1)
            .filter { it.value.isNotBlank() }
            .map { IndexedValue(it.index + 1, it.value) }

    /** The time of the newest complete row, or null when nothing is recorded. */
    fun lastTime(): Long? =
        days().asReversed().firstNotNullOfOrNull { file ->
            rows(file)
                .lastOrNull()
                ?.value
                ?.substringBefore(',')
                ?.toLongOrNull()
        }

    /** Appends [row] (no newline) to the file of [timeMs]'s day, creating it with its header. */
    fun append(
        timeMs: Long,
        row: String,
    ) {
        val file = dir.resolve("${day(timeMs)}.csv")
        Files.createDirectories(dir)
        if (!Files.exists(file) || Files.size(file) == 0L) Files.writeString(file, header) else cutTornLine(file)
        Files.writeString(file, "$row\n", StandardOpenOption.APPEND)
    }

    /**
     * Adds [rows] (by time) to their day files, each rewritten whole and moved into place, so a crash leaves
     * every day file either as it was or complete. A row already recorded at the same time is kept once.
     */
    fun merge(rows: Map<Long, String>) {
        Files.createDirectories(dir)
        rows.entries.groupBy { day(it.key) }.forEach { (day, added) ->
            val file = dir.resolve("$day.csv")
            val held = sortedMapOf<Long, String>()
            if (Files.exists(file)) rows(file).forEach { held[it.value.substringBefore(',').toLong()] = it.value }
            added.forEach { held[it.key] = it.value }
            val temporary = dir.resolve("$day.csv.tmp")
            Files.writeString(temporary, header + held.values.joinToString("") { "$it\n" })
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** Deletes the day files before [keepFrom]; returns how many. */
    fun prune(keepFrom: LocalDate): Int {
        val old = days(last = keepFrom.minusDays(1))
        old.forEach(Files::delete)
        return old.size
    }

    private fun complete(file: Path): String {
        val text = Files.readString(file)
        return text.substring(0, text.lastIndexOf('\n') + 1)
    }

    private fun cutTornLine(file: Path) {
        val bytes = Files.readAllBytes(file)
        val complete = bytes.lastIndexOf('\n'.code.toByte()) + 1
        if (complete == bytes.size) return
        log.warn("{}: dropping a torn last line (an append cut short)", file)
        FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(complete.toLong()) }
    }

    /** Day arithmetic shared by the recorders. */
    companion object {
        private val DAY_FILE = Regex("""\d{4}-\d{2}-\d{2}\.csv""")

        /** The UTC day [ms] falls in. */
        fun day(ms: Long): LocalDate = LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC)

        private fun dayOf(file: Path) = LocalDate.parse(file.fileName.toString().removeSuffix(".csv"))
    }
}
