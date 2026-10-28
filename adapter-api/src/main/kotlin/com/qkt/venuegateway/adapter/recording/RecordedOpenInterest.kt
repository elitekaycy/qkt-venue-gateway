package com.qkt.venuegateway.adapter.recording

import com.qkt.venuegateway.adapter.VenueOpenInterest
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import org.slf4j.LoggerFactory

/**
 * A contract's open interest as the gateway recorded it, for a venue that publishes only the present figure (wire
 * spec `/v1/open-interest`): a read whose window reaches the present (within [PRESENT_MS]) first records the figure
 * as it stands (`now`, the adapter's call, at the venue's time), then serves what was recorded in the window.
 * Recorded under `open-interest/` in [stateDir], a directory a code, a file a UTC day ([DayFiles]: a
 * `time,open_interest` header, then rows oldest first), kept [retentionDays] days ([prune]). A record an older
 * gateway kept in one file, `open-interest/<code>.csv`, is split into day files the first time the code is read,
 * losing nothing. A series starts when the gateway first read it and holds one figure per read.
 */
class RecordedOpenInterest(
    private val stateDir: Path,
    private val clock: () -> Long,
    retentionDays: Int,
) {
    private val newest = HashMap<String, Long>()
    private val recording = Recording(stateDir.resolve("open-interest"), HEADER, retentionDays, clock)
    private val log = LoggerFactory.getLogger(RecordedOpenInterest::class.java)

    /** [code]'s figures from [fromMs] to [toMs], oldest first, after recording [now]'s when the window reaches now. */
    fun read(
        code: String,
        fromMs: Long,
        toMs: Long,
        now: () -> VenueOpenInterest?,
    ): List<VenueOpenInterest> {
        if (toMs >= clock() - PRESENT_MS) now()?.let { record(code, it) }
        synchronized(newest) {
            if (fromMs > toMs) return emptyList()
            val series = series(code)
            return series
                .days(DayFiles.day(fromMs), DayFiles.day(toMs))
                .flatMap { file -> series.rows(file).map { (n, line) -> parse(file, n, line) } }
                .filter { it.timeMs in fromMs..toMs }
                .distinctBy { it.timeMs }
                .sortedBy { it.timeMs }
        }
    }

    /** Deletes the day files older than the retention window, at most once a UTC day; returns how many. */
    fun prune(): Int = synchronized(newest) { recording.prune() }

    private fun record(
        code: String,
        figure: VenueOpenInterest,
    ) = synchronized(newest) {
        val series = series(code)
        val last = newest.getOrPut(code) { series.lastTime() ?: Long.MIN_VALUE }
        if (figure.timeMs <= last) return@synchronized
        series.append(listOf(figure.timeMs to "${figure.timeMs},${figure.openInterest.toPlainString()}"))
        newest[code] = figure.timeMs
    }

    /** [code]'s day files, after splitting the single file an older gateway kept, if there is one. */
    private fun series(code: String): DayFiles {
        val series = recording.series(code)
        val single = stateDir.resolve("open-interest").resolve("$code.csv")
        if (Files.exists(single)) split(single, series)
        return series
    }

    /**
     * Moves [single]'s figures into [series]'s day files, then deletes it; a crash before the delete splits it again
     * on the next read, the same figures kept once. A torn last line is dropped with a warning; any other line that
     * is not `time,open_interest` fails naming the file and line, the file left as it is.
     */
    private fun split(
        single: Path,
        series: DayFiles,
    ) {
        val text = Files.readString(single)
        val complete = text.lastIndexOf('\n') + 1
        if (complete < text.length) {
            log.warn("{}: dropping a torn last line (an append cut short): {}", single, text.substring(complete))
        }
        val figures = TreeMap<Long, String>()
        text.substring(0, complete).lines().withIndex().drop(1).filter { it.value.isNotBlank() }.forEach { (i, line) ->
            figures[parse(single, i + 1, line).timeMs] = line
        }
        series.merge(figures)
        Files.delete(single)
        log.info("{}: split {} figures into day files", single, figures.size)
    }

    private fun parse(
        file: Path,
        n: Int,
        line: String,
    ): VenueOpenInterest {
        val cells = line.split(',')
        val time = cells.getOrNull(0)?.toLongOrNull()
        val figure = cells.getOrNull(1)?.toBigDecimalOrNull()
        require(cells.size == 2 && time != null && figure != null) {
            "$file line $n: expected time,open_interest: $line"
        }
        return VenueOpenInterest(time, figure)
    }

    private companion object {
        /** How close to now a window must end for a read to record the present figure first. */
        const val PRESENT_MS = 60_000L
        const val HEADER = "time,open_interest\n"
    }
}
