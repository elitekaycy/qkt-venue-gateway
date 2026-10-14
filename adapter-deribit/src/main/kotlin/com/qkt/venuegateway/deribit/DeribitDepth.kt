package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueLevel
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.deribit.DeribitDayFiles.Companion.day
import com.qkt.venuegateway.deribit.DeribitErrors.venue
import com.qkt.venuegateway.deribit.client.DeribitOrderBook
import java.math.BigDecimal
import java.nio.file.Path

/**
 * Deribit's order book as this gateway recorded it. Deribit publishes no book history, so a read that reaches
 * the present ([PRESENT_MS]) first takes the book as it stands ([book]: `public/get_order_book` of a code, its
 * best [VenueDepth.MAX_LEVELS] levels a side) and records it at Deribit's own stamp, then serves what was
 * recorded in the window. The record is one file a UTC day under [stateDir], `depth/<code>/<yyyy-MM-dd>.csv`
 * (`time,bids,asks`, each side `price:amount` levels best first, space separated), kept across restarts for
 * [retentionDays] days ([DeribitRetention], 0 for ever; [prune]). A series starts when the gateway first read
 * it and holds one snapshot per read: a gap where nothing read it stays a gap. Shared by every adapter
 * quoting Deribit's public market.
 */
class DeribitDepth(
    private val book: (code: String, levels: Int) -> DeribitOrderBook,
    stateDir: Path,
    private val clock: () -> Long,
    retentionDays: Int = 0,
) {
    private val newest = HashMap<String, Long>()
    private val recording = DeribitRecording(stateDir.resolve("depth"), HEADER, retentionDays, clock)

    /** [code]'s recorded snapshots from [fromMs] to [toMs], oldest first, after recording the present one when the window reaches it. */
    fun read(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueDepth> {
        if (!CODE.matches(code)) throw VenueRefusedException("$code is not a Deribit instrument name")
        if (toMs >= clock() - PRESENT_MS) {
            record(code, DeribitMarketMapping.depth(venue { book(code, VenueDepth.MAX_LEVELS) }))
        }
        if (fromMs > toMs) return emptyList()
        synchronized(newest) {
            val series = recording.series(code)
            return series.days(day(fromMs), day(toMs)).flatMap { file ->
                series.rows(file).mapNotNull { (n, line) -> parse(file, n, line, fromMs..toMs) }
            }
        }
    }

    /** Deletes the day files older than the retention window, at most once a UTC day; returns how many. */
    fun prune(): Int = synchronized(newest) { recording.prune() }

    private fun record(
        code: String,
        snapshot: VenueDepth,
    ) = synchronized(newest) {
        val series = recording.series(code)
        val last = newest.getOrPut(code) { series.lastTime() ?: -1 }
        if (snapshot.timeMs <= last) return@synchronized
        series.append(snapshot.timeMs, line(snapshot))
        newest[code] = snapshot.timeMs
    }

    /**
     * Line [n] of [file] when its time is [within], else null. A complete line that is not `time,bids,asks` fails
     * naming the file and line, as a record no one can trust.
     */
    private fun parse(
        file: Path,
        n: Int,
        line: String,
        within: LongRange,
    ): VenueDepth? {
        val cells = line.split(',')
        val time = cells[0].toLongOrNull()
        require(cells.size == 3 && time != null) { "$file line $n: expected time,bids,asks: $line" }
        if (time !in within) return null
        val parsed = runCatching { VenueDepth(time, levels(cells[1]), levels(cells[2])) }
        return parsed.getOrElse { throw IllegalArgumentException("$file line $n: unreadable levels: $line", it) }
    }

    private companion object {
        /** How close to now a window must end for a read to record the present book first. */
        const val PRESENT_MS = 60_000L
        const val HEADER = "time,bids,asks\n"
        val CODE = Regex("[A-Za-z0-9_-]+")

        fun line(d: VenueDepth) = "${d.timeMs},${side(d.bids)},${side(d.asks)}"

        fun side(levels: List<VenueLevel>) =
            levels.joinToString(" ") { "${it.price.toPlainString()}:${it.amount.toPlainString()}" }

        fun levels(cell: String): List<VenueLevel> =
            cell.split(' ').filter { it.isNotEmpty() }.map {
                val (price, amount) = it.split(':')
                VenueLevel(BigDecimal(price), BigDecimal(amount))
            }
    }
}
