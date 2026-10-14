package com.qkt.venuegateway.deribit

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.slf4j.LoggerFactory

/**
 * Every contract's recorded series of one kind under [root], one directory a contract of day files
 * ([DeribitDayFiles], each starting with [header]), kept for [retentionDays] UTC days: today and the
 * [retentionDays] days before it. 0 keeps everything. Not thread-safe: its recorder calls it under one lock.
 */
internal class DeribitRecording(
    private val root: Path,
    private val header: String,
    private val retentionDays: Int,
    private val clock: () -> Long,
) {
    private var prunedOn: LocalDate? = null
    private val log = LoggerFactory.getLogger(DeribitRecording::class.java)

    /** [code]'s series. */
    fun series(code: String) = DeribitDayFiles(root.resolve(code), header)

    /**
     * Deletes every contract's day files older than the window, at most once a UTC day (the first call
     * always runs); returns how many. A read of a pruned range then serves what remains.
     */
    fun prune(): Int {
        if (retentionDays == 0) return 0
        val today = DeribitDayFiles.day(clock())
        if (prunedOn == today) return 0
        val keepFrom = today.minusDays(retentionDays.toLong())
        val deleted =
            if (!Files.isDirectory(root)) {
                0
            } else {
                Files.list(root).use { dirs -> dirs.filter(Files::isDirectory).toList() }.sumOf {
                    DeribitDayFiles(it, header).prune(keepFrom)
                }
            }
        prunedOn = today
        if (deleted >
            0
        ) {
            log.info("{}: deleted {} day files before {} ({} days kept)", root, deleted, keepFrom, retentionDays)
        }
        return deleted
    }
}
