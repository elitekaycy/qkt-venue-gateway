package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueOpenInterest
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.TreeMap

/**
 * Deribit's open interest as this gateway recorded it. Deribit publishes no open-interest history, only the
 * present figure on an instrument's ticker, so a read that reaches the present ([PRESENT_MS]) first records
 * the ticker's figure at the ticker's time, then serves what was recorded in the window. The record is kept
 * in `open-interest/<code>.csv` under [stateDir] (`time,open_interest`, oldest first) across restarts. A
 * series starts when the gateway first read it and holds one figure per read: a gap where nothing read it
 * stays a gap. [ticker] reads an instrument's ticker, its failures the adapter's own. Shared by every adapter
 * quoting Deribit's public market.
 */
class DeribitOpenInterest(
    private val ticker: (code: String) -> DeribitTicker,
    private val stateDir: Path,
    private val clock: () -> Long,
) {
    private val series = HashMap<String, TreeMap<Long, BigDecimal>>()

    /** [code]'s recorded figures from [fromMs] to [toMs], oldest first, after recording the present one when the window reaches it. */
    fun read(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueOpenInterest> {
        if (!CODE.matches(code)) throw VenueRefusedException("$code is not a Deribit instrument name")
        if (toMs >= clock() - PRESENT_MS) DeribitMarketMapping.openInterest(ticker(code))?.let { record(code, it) }
        synchronized(series) {
            if (fromMs > toMs) return emptyList()
            return recorded(code).subMap(fromMs, true, toMs, true).map { (time, figure) ->
                VenueOpenInterest(time, figure)
            }
        }
    }

    private fun record(
        code: String,
        figure: VenueOpenInterest,
    ) = synchronized(series) {
        val held = recorded(code)
        if (held.isNotEmpty() && figure.timeMs <= held.lastKey()) return@synchronized
        held[figure.timeMs] = figure.openInterest
        val file = file(code)
        Files.createDirectories(file.parent)
        if (!Files.exists(file)) Files.writeString(file, HEADER)
        Files.writeString(file, "${figure.timeMs},${figure.openInterest.toPlainString()}\n", StandardOpenOption.APPEND)
    }

    private fun recorded(code: String): TreeMap<Long, BigDecimal> =
        series.getOrPut(code) {
            val file = file(code)
            val held = TreeMap<Long, BigDecimal>()
            if (Files.exists(file)) {
                Files.readAllLines(file).drop(1).filter { it.isNotBlank() }.forEachIndexed { i, line ->
                    val cells = line.split(',')
                    require(cells.size == 2) { "$file line ${i + 2}: expected time,open_interest: $line" }
                    held[cells[0].toLong()] = BigDecimal(cells[1])
                }
            }
            held
        }

    private fun file(code: String): Path = stateDir.resolve("open-interest").resolve("$code.csv")

    private companion object {
        /** How close to now a window must end for a read to record the present figure first. */
        const val PRESENT_MS = 60_000L
        const val HEADER = "time,open_interest\n"
        val CODE = Regex("[A-Za-z0-9_-]+")
    }
}
