package com.qkt.venuegateway.adapter.recording

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenuePrint
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A public tape and its liquidations as the gateway heard them, for a venue that keeps no history of either (wire
 * spec `/v1/trades`, `/v1/liquidations`). Every code a client asks for is taped from then on ([tape]: the adapter
 * subscribes its pushes, which it hands to [onTrades] and [onLiquidations]), across restarts (the codes are kept in
 * `taped.txt` under [stateDir], [resume]), and a read of the tape first records the venue's latest trades (`recent`),
 * so a code read for the first time is served its recent past. Recorded under `trades/` and `liquidations/`
 * ([DayFiles], no header: `time,id,price,size,side`, the side `Buy` or `Sell`: a trade's aggressor, a liquidation's
 * position liquidated, the reverse of the liquidation order's [VenuePrint.side]), kept [retentionDays] days.
 */
class RecordedTape(
    private val stateDir: Path,
    clock: () -> Long,
    retentionDays: Int,
    private val tape: (codes: Collection<String>) -> Unit,
) {
    private val trades = Recording(stateDir.resolve("trades"), null, retentionDays, clock)
    private val liquidations = Recording(stateDir.resolve("liquidations"), null, retentionDays, clock)
    private val tapedFile = stateDir.resolve("taped.txt")
    private val taped = HashSet<String>()

    /** Tapes again every code taped before a restart. */
    fun resume() {
        val codes =
            synchronized(this) {
                if (Files.exists(tapedFile)) taped += Files.readAllLines(tapedFile).filter(String::isNotBlank)
                taped.toList()
            }
        if (codes.isNotEmpty()) tape(codes)
    }

    /** Records [prints] the venue pushed of [code]'s tape. */
    fun onTrades(
        code: String,
        prints: List<VenuePrint>,
    ) = synchronized(this) { trades.series(code).append(prints.map { it.timeMs to line(it, it.side) }) }

    /** Records [prints] the venue pushed of [code]'s liquidations. */
    fun onLiquidations(
        code: String,
        prints: List<VenuePrint>,
    ) = synchronized(this) { liquidations.series(code).append(prints.map { it.timeMs to line(it, opposite(it.side)) }) }

    /** The first [limit] prints of [code] in `[fromMs, toMs)`, oldest first, after recording the venue's latest. */
    fun trades(
        code: String,
        fromMs: Long,
        toMs: Long,
        limit: Int,
        recent: () -> List<VenuePrint>,
    ): List<VenuePrint> {
        watch(code)
        val latest = recent()
        return synchronized(this) {
            if (latest.isNotEmpty()) {
                val held = read(trades, code, latest.first().timeMs, latest.last().timeMs, false).map { it.id }.toSet()
                trades.series(code).append(latest.filter { it.id !in held }.map { it.timeMs to line(it, it.side) })
            }
            read(trades, code, fromMs, toMs - 1, false).take(limit)
        }
    }

    /** Every liquidation of [code] heard in `[fromMs, toMs)`, oldest first. */
    fun liquidations(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenuePrint> {
        watch(code)
        return synchronized(this) { read(liquidations, code, fromMs, toMs - 1, true) }
    }

    /** Deletes the day files past their retention, at most once a UTC day; returns how many. */
    fun prune() = synchronized(this) { trades.prune() + liquidations.prune() }

    private fun watch(code: String) {
        val added = synchronized(this) { taped.add(code).also { if (it) save(code) } }
        if (added) tape(listOf(code))
    }

    private fun save(code: String) {
        Files.createDirectories(stateDir)
        Files.writeString(tapedFile, "$code\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun read(
        recording: Recording,
        code: String,
        fromMs: Long,
        toMs: Long,
        liquidated: Boolean,
    ): List<VenuePrint> {
        if (fromMs > toMs) return emptyList()
        val series = recording.series(code)
        return series
            .days(DayFiles.day(fromMs), DayFiles.day(toMs))
            .flatMap { file -> series.rows(file).map { parse(it.value, liquidated) } }
            .filter { it.timeMs in fromMs..toMs }
            .distinctBy { it.id }
            .sortedBy { it.timeMs }
    }

    private companion object {
        fun line(
            p: VenuePrint,
            side: Side,
        ) = "${p.timeMs},${p.id},${p.price.toPlainString()},${p.size.toPlainString()},${word(side)}"

        fun parse(
            line: String,
            liquidated: Boolean,
        ): VenuePrint {
            val cells = line.split(',')
            require(cells.size == 5) { "a taped line is time,id,price,size,side: $line" }
            val side = side(cells[4]).let { if (liquidated) opposite(it) else it }
            return VenuePrint(cells[1], cells[0].toLong(), BigDecimal(cells[2]), BigDecimal(cells[3]), side)
        }

        fun word(side: Side) = if (side == Side.BUY) "Buy" else "Sell"

        fun side(word: String) =
            when (word) {
                "Buy" -> Side.BUY
                "Sell" -> Side.SELL
                else -> error("a taped side is Buy or Sell: $word")
            }

        fun opposite(side: Side) = if (side == Side.BUY) Side.SELL else Side.BUY
    }
}
