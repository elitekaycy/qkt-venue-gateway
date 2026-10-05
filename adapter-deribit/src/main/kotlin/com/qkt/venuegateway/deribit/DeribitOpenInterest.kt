package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueOpenInterest
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.TreeMap
import org.slf4j.LoggerFactory

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
    private val log = LoggerFactory.getLogger(DeribitOpenInterest::class.java)

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
        if (!Files.exists(file) || Files.size(file) == 0L) Files.writeString(file, HEADER)
        Files.writeString(file, "${figure.timeMs},${figure.openInterest.toPlainString()}\n", StandardOpenOption.APPEND)
    }

    private fun recorded(code: String): TreeMap<Long, BigDecimal> = series.getOrPut(code) { load(file(code)) }

    /**
     * The figures in [file]. A last line without its newline is an append a crash cut short: it is dropped and
     * the file truncated back to its last complete line, with a warning naming the file. Any complete line
     * that is not `time,open_interest` fails naming the file and line, as a record no one can trust.
     */
    private fun load(file: Path): TreeMap<Long, BigDecimal> {
        val held = TreeMap<Long, BigDecimal>()
        if (!Files.exists(file)) return held
        val bytes = Files.readAllBytes(file)
        val complete = bytes.lastIndexOf('\n'.code.toByte()) + 1
        if (complete < bytes.size) {
            log.warn(
                "{}: dropping a torn last line (an append cut short): {}",
                file,
                String(
                    bytes,
                    complete,
                    bytes.size - complete,
                ),
            )
            FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(complete.toLong()) }
        }
        String(bytes, 0, complete).lines().drop(1).filter { it.isNotBlank() }.forEachIndexed { i, line ->
            val cells = line.split(',')
            val time = cells.getOrNull(0)?.toLongOrNull()
            val figure = cells.getOrNull(1)?.toBigDecimalOrNull()
            require(cells.size == 2 && time != null && figure != null) {
                "$file line ${i + 2}: expected time,open_interest: $line"
            }
            held[time] = figure
        }
        return held
    }

    private fun file(code: String): Path = stateDir.resolve("open-interest").resolve("$code.csv")

    private companion object {
        /** How close to now a window must end for a read to record the present figure first. */
        const val PRESENT_MS = 60_000L
        const val HEADER = "time,open_interest\n"
        val CODE = Regex("[A-Za-z0-9_-]+")
    }
}
