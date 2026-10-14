package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueOpenInterest
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.deribit.DeribitDayFiles.Companion.day
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import org.slf4j.LoggerFactory

/**
 * Deribit's open interest as this gateway recorded it. Deribit publishes no open-interest history, only the
 * present figure on an instrument's ticker, so a read that reaches the present ([PRESENT_MS]) first records
 * the ticker's figure at the ticker's time, then serves what was recorded in the window. The record is one
 * file a UTC day under [stateDir], `open-interest/<code>/<yyyy-MM-dd>.csv` (`time,open_interest`, oldest
 * first), kept across restarts for [retentionDays] days ([DeribitRetention], 0 for ever; [prune]). A record
 * kept by an older gateway in one file, `open-interest/<code>.csv`, is split into day files the first time
 * the code is read, losing nothing. A series starts when the gateway first read it and holds one figure per
 * read: a gap where nothing read it stays a gap. [ticker] reads an instrument's ticker, its failures the
 * adapter's own. Shared by every adapter quoting Deribit's public market.
 */
class DeribitOpenInterest(
    private val ticker: (code: String) -> DeribitTicker,
    private val stateDir: Path,
    private val clock: () -> Long,
    retentionDays: Int = 0,
) {
    private val newest = HashMap<String, Long>()
    private val recording = DeribitRecording(stateDir.resolve("open-interest"), HEADER, retentionDays, clock)
    private val log = LoggerFactory.getLogger(DeribitOpenInterest::class.java)

    /** [code]'s recorded figures from [fromMs] to [toMs], oldest first, after recording the present one when the window reaches it. */
    fun read(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueOpenInterest> {
        if (!CODE.matches(code)) throw VenueRefusedException("$code is not a Deribit instrument name")
        if (toMs >= clock() - PRESENT_MS) DeribitMarketMapping.openInterest(ticker(code))?.let { record(code, it) }
        synchronized(newest) {
            if (fromMs > toMs) return emptyList()
            val series = series(code)
            return series.days(day(fromMs), day(toMs)).flatMap { file ->
                series.rows(file).map { (n, line) -> parse(file, n, line) }.filter { it.timeMs in fromMs..toMs }
            }
        }
    }

    /** Deletes the day files older than the retention window, at most once a UTC day; returns how many. */
    fun prune(): Int = synchronized(newest) { recording.prune() }

    private fun record(
        code: String,
        figure: VenueOpenInterest,
    ) = synchronized(newest) {
        val series = series(code)
        val last = newest.getOrPut(code) { series.lastTime() ?: -1 }
        if (figure.timeMs <= last) return@synchronized
        series.append(figure.timeMs, "${figure.timeMs},${figure.openInterest.toPlainString()}")
        newest[code] = figure.timeMs
    }

    /** [code]'s day files, after splitting the single file an older gateway kept, if there is one. */
    private fun series(code: String): DeribitDayFiles {
        val series = recording.series(code)
        val single = stateDir.resolve("open-interest").resolve("$code.csv")
        if (Files.exists(single)) split(single, series)
        return series
    }

    /**
     * Moves [single]'s figures into [series]'s day files, then deletes it; a crash before the delete splits it
     * again on the next read, the same figures kept once. A last line without its newline is an append a crash
     * cut short and is dropped with a warning; any other line that is not `time,open_interest` fails naming the
     * file and line, the file left as it is.
     */
    private fun split(
        single: Path,
        series: DeribitDayFiles,
    ) {
        val text = Files.readString(single)
        val complete = text.lastIndexOf('\n') + 1
        if (complete <
            text.length
        ) {
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
        require(
            cells.size == 2 && time != null && figure != null,
        ) { "$file line $n: expected time,open_interest: $line" }
        return VenueOpenInterest(time, figure)
    }

    private companion object {
        /** How close to now a window must end for a read to record the present figure first. */
        const val PRESENT_MS = 60_000L
        const val HEADER = "time,open_interest\n"
        val CODE = Regex("[A-Za-z0-9_-]+")
    }
}
