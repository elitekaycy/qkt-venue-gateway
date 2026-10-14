package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.VenuePrint
import com.qkt.venuegateway.bybit.client.BybitPrint
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Bybit's public tape and liquidations as this gateway recorded them. Bybit keeps no history of either: its
 * REST answers only a contract's latest trades ([recent]: 1000 of a contract, 60 of a spot pair) and no
 * liquidation at all. So every code a client asks for is taped from then on ([tape]: Bybit's `publicTrade`
 * and `allLiquidation` pushes, [onTrades], [onLiquidations]), across restarts (the codes are kept in
 * `taped.txt` under [stateDir]), and a read of the tape first records Bybit's latest trades, so a code read
 * for the first time is served its recent past. A series holds what the gateway heard: liquidations start when
 * the code was first asked for, and a span the gateway was down for holds only what the latest trades still
 * covered. Recorded under `trades/` and `liquidations/` ([BybitRecording], kept [retentionDays] days).
 */
internal class BybitTape(
    private val stateDir: Path,
    clock: () -> Long,
    retentionDays: Int,
    private val recent: (code: String) -> List<BybitPrint>,
    private val tape: (codes: Collection<String>) -> Unit,
) {
    private val trades = BybitRecording(stateDir.resolve("trades"), retentionDays, clock)
    private val liquidations = BybitRecording(stateDir.resolve("liquidations"), retentionDays, clock)
    private val tapedFile = stateDir.resolve("taped.txt")
    private val taped = HashSet<String>()

    /** Tapes again every code taped before a restart. */
    fun resume() {
        val codes =
            synchronized(this) {
                if (Files.exists(tapedFile)) {
                    taped +=
                        Files.readAllLines(tapedFile).filter(String::isNotBlank)
                }
                ; taped.toList()
            }
        if (codes.isNotEmpty()) tape(codes)
    }

    fun onTrades(
        code: String,
        prints: List<BybitPrint>,
    ) = synchronized(this) { trades.append(code, prints.map { it.timeMs to line(it) }) }

    fun onLiquidations(
        code: String,
        prints: List<BybitPrint>,
    ) = synchronized(this) { liquidations.append(code, prints.map { it.timeMs to line(it) }) }

    /** The first [limit] prints of [code] in `[fromMs, toMs)`, oldest first, after recording Bybit's latest. */
    fun trades(
        code: String,
        fromMs: Long,
        toMs: Long,
        limit: Int,
    ): List<VenuePrint> {
        watch(code)
        val latest = recent(code)
        return synchronized(this) {
            if (latest.isNotEmpty()) {
                val held = read(trades, code, latest.first().timeMs, latest.last().timeMs).map { it.id }.toSet()
                trades.append(code, latest.filter { it.id !in held }.map { it.timeMs to line(it) })
            }
            read(trades, code, fromMs, toMs - 1).take(limit).map(BybitMarketMapping::print)
        }
    }

    /** Every liquidation of [code] the gateway heard in `[fromMs, toMs)`, oldest first. */
    fun liquidations(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenuePrint> {
        watch(code)
        return synchronized(this) { read(liquidations, code, fromMs, toMs - 1).map(BybitMarketMapping::liquidation) }
    }

    /** Deletes the day files past their retention, at most once a UTC day. */
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
        recording: BybitRecording,
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<BybitPrint> =
        recording
            .lines(code, fromMs, toMs)
            .map(::parse)
            .filter { it.timeMs in fromMs..toMs }
            .distinctBy { it.id }
            .sortedBy { it.timeMs }

    private fun line(p: BybitPrint) =
        "${p.timeMs},${p.id},${p.price.toPlainString()},${p.size.toPlainString()},${p.side}"

    private fun parse(line: String): BybitPrint {
        val cells = line.split(',')
        require(cells.size == 5) { "a taped line is time,id,price,size,side: $line" }
        return BybitPrint(cells[1], cells[0].toLong(), BigDecimal(cells[2]), BigDecimal(cells[3]), cells[4])
    }
}
