package com.qkt.venuegateway.adapter.recording

import java.nio.ByteBuffer
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
 * One code's recorded series: a file a UTC day in [dir] (`<yyyy-MM-dd>.csv`), each row's first cell its time in
 * milliseconds, starting with [header] when the series has one (a series keeps the format it was first written
 * in: Deribit's carry a header line, Bybit's none). A last line without its newline is an append a crash cut short:
 * it is never served, and it is cut off before the next append. Not thread-safe: its recorder calls it under one lock.
 */
class DayFiles(
    private val dir: Path,
    private val header: String?,
) {
    private val log = LoggerFactory.getLogger(DayFiles::class.java)

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
    fun rows(file: Path): List<IndexedValue<String>> {
        val text = Files.readString(file)
        return text
            .substring(0, text.lastIndexOf('\n') + 1)
            .lines()
            .withIndex()
            .drop(if (header == null) 0 else 1)
            .filter { it.value.isNotBlank() }
            .map { IndexedValue(it.index + 1, it.value) }
    }

    /** The time of the newest complete row of the newest day file, or null when nothing is recorded. */
    fun lastTime(): Long? =
        days().asReversed().firstNotNullOfOrNull { file ->
            rows(file).mapNotNull { it.value.substringBefore(',').toLongOrNull() }.maxOrNull()
        }

    /** Appends [rows] (time and line, no newline), each to the file of its time's day, creating it with its header. */
    fun append(rows: List<Pair<Long, String>>) {
        Files.createDirectories(dir)
        rows.groupBy { day(it.first) }.forEach { (day, held) ->
            val file = dir.resolve("$day.csv")
            val fresh = !Files.exists(file) || Files.size(file) == 0L
            if (fresh && header != null) {
                Files.writeString(file, header)
            } else if (!fresh) {
                cutTornLine(file)
            }
            val text = held.joinToString("") { "${it.second}\n" }
            Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    /**
     * Adds [rows] (by time) to their day files, each rewritten whole and moved into place, so a crash leaves every
     * day file either as it was or complete. A row already recorded at the same time is kept once.
     */
    fun merge(rows: Map<Long, String>) {
        Files.createDirectories(dir)
        rows.entries.groupBy { day(it.key) }.forEach { (day, added) ->
            val file = dir.resolve("$day.csv")
            val held = sortedMapOf<Long, String>()
            if (Files.exists(file)) rows(file).forEach { held[it.value.substringBefore(',').toLong()] = it.value }
            added.forEach { held[it.key] = it.value }
            val temporary = dir.resolve("$day.csv.tmp")
            Files.writeString(temporary, (header ?: "") + held.values.joinToString("") { "$it\n" })
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** Deletes the day files before [keepFrom]; returns how many. */
    fun prune(keepFrom: LocalDate): Int {
        val old = days(last = keepFrom.minusDays(1))
        old.forEach(Files::delete)
        return old.size
    }

    /** Cuts a last line left without its newline, reading the whole file only when its last byte says so. */
    private fun cutTornLine(file: Path) {
        val torn =
            FileChannel.open(file).use { channel ->
                val last = ByteBuffer.allocate(1)
                channel.read(last, channel.size() - 1)
                last.get(0) != NEWLINE
            }
        if (!torn) return
        val complete = Files.readAllBytes(file).lastIndexOf(NEWLINE) + 1
        log.warn("{}: dropping a torn last line (an append cut short)", file)
        FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(complete.toLong()) }
    }

    /** Day arithmetic shared by the recorders. */
    companion object {
        private const val NEWLINE = '\n'.code.toByte()
        private val DAY_FILE = Regex("""\d{4}-\d{2}-\d{2}\.csv""")
        private val MIN_MS = Instant.parse("0000-01-01T00:00:00Z").toEpochMilli()
        private val MAX_MS = Instant.parse("9999-12-31T23:59:59Z").toEpochMilli()

        /** The UTC day [ms] falls in, held within years 0 to 9999 (the days a file can name). */
        fun day(ms: Long): LocalDate =
            LocalDate.ofInstant(Instant.ofEpochMilli(ms.coerceIn(MIN_MS, MAX_MS)), ZoneOffset.UTC)

        private fun dayOf(file: Path) = LocalDate.parse(file.fileName.toString().removeSuffix(".csv"))
    }
}
