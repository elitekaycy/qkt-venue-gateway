package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueLevel
import com.qkt.venuegateway.bybit.BybitErrors.venue
import com.qkt.venuegateway.bybit.client.BybitBook
import java.math.BigDecimal
import java.nio.file.Path

/**
 * Bybit's order book as this gateway recorded it. Bybit publishes no book history, so a read that reaches the
 * present ([PRESENT_MS]) first takes the book as it stands ([book]: its best [VenueDepth.MAX_LEVELS] levels a
 * side) and records it at Bybit's own stamp, then serves what was recorded in the window: one snapshot per
 * read, a series starting when the gateway first read it. Recorded under `depth/` in [stateDir]
 * (`time,bids,asks`, each side `price:amount` levels best first, space separated), kept [retentionDays] days.
 */
internal class BybitDepth(
    private val book: (code: String) -> BybitBook,
    stateDir: Path,
    private val clock: () -> Long,
    retentionDays: Int,
) {
    private val recording = BybitRecording(stateDir.resolve("depth"), retentionDays, clock)
    private val newest = HashMap<String, Long>()

    fun read(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueDepth> {
        if (toMs >= clock() - PRESENT_MS) {
            val present = BybitMarketMapping.depth(venue { book(code) })
            synchronized(this) {
                val last = newest[code] ?: Long.MIN_VALUE
                if (present.timeMs > last) {
                    recording.append(code, listOf(present.timeMs to line(present)))
                    newest[code] = present.timeMs
                }
            }
        }
        return synchronized(this) {
            recording
                .lines(code, fromMs, toMs)
                .map(::parse)
                .filter { it.timeMs in fromMs..toMs }
                .distinctBy { it.timeMs }
                .sortedBy { it.timeMs }
        }
    }

    /** Deletes the day files past their retention, at most once a UTC day. */
    fun prune() = synchronized(this) { recording.prune() }

    private fun line(d: VenueDepth) = "${d.timeMs},${side(d.bids)},${side(d.asks)}"

    private fun side(levels: List<VenueLevel>) =
        levels.joinToString(" ") {
            "${it.price.toPlainString()}:${it.amount.toPlainString()}"
        }

    private fun parse(line: String): VenueDepth {
        val cells = line.split(',')
        require(cells.size == 3) { "a depth line is time,bids,asks: $line" }
        return VenueDepth(cells[0].toLong(), levels(cells[1]), levels(cells[2]))
    }

    private fun levels(cell: String) =
        cell.split(' ').filter(String::isNotEmpty).map {
            val (price, amount) = it.split(':')
            VenueLevel(BigDecimal(price), BigDecimal(amount))
        }

    private companion object {
        const val PRESENT_MS = 60_000L
    }
}
