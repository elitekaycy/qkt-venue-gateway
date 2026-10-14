package com.qkt.venuegateway.bybit

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Every contract's recorded series of one kind under [root]: a directory a contract, a file a UTC day
 * (`<code>/<yyyy-MM-dd>.csv`, each line's first cell its time in milliseconds), kept for [retentionDays] days
 * (today and that many before it; 0 keeps everything). Lines are appended as they come, so a day's file may
 * be out of time order; a reader sorts. A last line without its newline is an append a crash cut short: it is
 * never read, and it is cut off before this process first appends to the file. Not thread-safe: its recorder calls it under one lock.
 */
internal class BybitRecording(
    private val root: Path,
    private val retentionDays: Int,
    private val clock: () -> Long,
) {
    private var prunedOn: LocalDate? = null
    private val checked = HashSet<Path>()

    /** Appends [lines] (no newlines) of [code], each to the file of its time's day. */
    fun append(
        code: String,
        lines: List<Pair<Long, String>>,
    ) {
        val dir = root.resolve(code)
        Files.createDirectories(dir)
        lines.groupBy { day(it.first) }.forEach { (day, rows) ->
            val file = dir.resolve("$day.csv")
            if (checked.add(file) && Files.exists(file)) cutTornLine(file)
            Files.writeString(
                file,
                rows.joinToString("") {
                    "${it.second}\n"
                },
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    /** [code]'s complete lines of the days from [fromMs]'s to [toMs]'s, as written. */
    fun lines(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<String> {
        val dir = root.resolve(code)
        if (!Files.isDirectory(dir) || fromMs > toMs) return emptyList()
        val days = day(fromMs)..day(toMs)
        val files =
            Files
                .list(dir)
                .use { paths ->
                    paths.filter { DAY_FILE.matches(it.fileName.toString()) }.toList()
                }.filter { dayOf(it) in days }
                .sorted()
        return files.flatMap { file ->
            val text = Files.readString(file)
            text
                .substring(0, text.lastIndexOf('\n') + 1)
                .lineSequence()
                .filter(String::isNotBlank)
                .toList()
        }
    }

    /** Deletes every contract's day files older than the window, at most once a UTC day; returns how many. */
    fun prune(): Int {
        val today = day(clock())
        if (retentionDays == 0 || prunedOn == today || !Files.isDirectory(root)) return 0
        val keepFrom = today.minusDays(retentionDays.toLong())
        val old =
            Files.walk(root, 2).use { paths ->
                paths
                    .filter { DAY_FILE.matches(it.fileName.toString()) }
                    .filter { dayOf(it).isBefore(keepFrom) }
                    .toList()
            }
        old.forEach(Files::delete)
        prunedOn = today
        return old.size
    }

    private fun cutTornLine(file: Path) {
        val bytes = Files.readAllBytes(file)
        val complete = bytes.lastIndexOf('\n'.code.toByte()) + 1
        if (complete !=
            bytes.size
        ) {
            FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(complete.toLong()) }
        }
    }

    /** Day arithmetic. */
    companion object {
        private val DAY_FILE = Regex("""\d{4}-\d{2}-\d{2}\.csv""")

        /** The UTC day [ms] falls in. */
        fun day(ms: Long): LocalDate =
            LocalDate.ofInstant(Instant.ofEpochMilli(ms.coerceIn(MIN_MS, MAX_MS)), ZoneOffset.UTC)

        private fun dayOf(file: Path): LocalDate = LocalDate.parse(file.fileName.toString().removeSuffix(".csv"))

        /** A day is taken of an instant held within years 0 to 9999, the days a file can name. */
        private val MIN_MS = Instant.parse("0000-01-01T00:00:00Z").toEpochMilli()
        private val MAX_MS = Instant.parse("9999-12-31T23:59:59Z").toEpochMilli()
    }
}
