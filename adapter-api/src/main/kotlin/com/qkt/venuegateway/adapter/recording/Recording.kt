package com.qkt.venuegateway.adapter.recording

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.slf4j.LoggerFactory

/**
 * Every code's recorded series of one kind under [root], a directory a code of [DayFiles] (each starting with
 * [header] when the kind has one), kept for [retentionDays] UTC days: today and that many days before it; 0 keeps
 * everything. Not thread-safe: its recorder calls it under one lock.
 */
class Recording(
    private val root: Path,
    private val header: String?,
    private val retentionDays: Int,
    private val clock: () -> Long,
) {
    private var prunedOn: LocalDate? = null
    private val log = LoggerFactory.getLogger(Recording::class.java)

    /** [code]'s series. */
    fun series(code: String) = DayFiles(root.resolve(code), header)

    /**
     * Deletes every code's day files older than the window, at most once a UTC day (the first call that finds
     * anything recorded always runs); returns how many. A read of a pruned range then serves what remains.
     */
    fun prune(): Int {
        val today = DayFiles.day(clock())
        if (retentionDays == 0 || prunedOn == today || !Files.isDirectory(root)) return 0
        val days = retentionDays
        val keepFrom = today.minusDays(days.toLong())
        val codes = Files.list(root).use { dirs -> dirs.filter(Files::isDirectory).toList() }
        val deleted = codes.sumOf { DayFiles(it, header).prune(keepFrom) }
        prunedOn = today
        if (deleted > 0) log.info("{}: deleted {} day files before {} ({} days kept)", root, deleted, keepFrom, days)
        return deleted
    }
}
