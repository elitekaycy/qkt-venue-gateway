package com.qkt.venuegateway.adapter.recording

import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueLevel
import java.math.BigDecimal
import java.nio.file.Path

/**
 * A contract's order book as the gateway recorded it (wire spec `/v1/depth`). Venues publish no book history, so a
 * read whose window reaches the present (within [PRESENT_MS]) first takes the book as it stands (`book`, the
 * adapter's call, at the venue's own stamp) and records it, then serves what was recorded in the window: one
 * snapshot per such read, a series starting when the gateway first read it, a gap wherever nothing read it.
 * Recorded under `depth/` in [stateDir], a directory a code, a file a UTC day ([DayFiles]): `time,bids,asks`, each
 * side `price:amount` levels best first, space separated, after a `time,bids,asks` header line when [headed]
 * (each venue's files keep the format they were first written in). Kept [retentionDays] days ([prune]).
 */
class RecordedDepth(
    stateDir: Path,
    headed: Boolean,
    private val clock: () -> Long,
    retentionDays: Int,
) {
    private val recording = Recording(stateDir.resolve("depth"), if (headed) HEADER else null, retentionDays, clock)
    private val newest = HashMap<String, Long>()

    /** [code]'s snapshots from [fromMs] to [toMs], oldest first, after recording [book] when the window reaches now. */
    fun read(
        code: String,
        fromMs: Long,
        toMs: Long,
        book: () -> VenueDepth,
    ): List<VenueDepth> {
        if (toMs >= clock() - PRESENT_MS) record(code, book())
        if (fromMs > toMs) return emptyList()
        return synchronized(newest) {
            val series = recording.series(code)
            series
                .days(DayFiles.day(fromMs), DayFiles.day(toMs))
                .flatMap { file -> series.rows(file).mapNotNull { (n, line) -> parse(file, n, line, fromMs..toMs) } }
                .distinctBy { it.timeMs }
                .sortedBy { it.timeMs }
        }
    }

    /** Deletes the day files older than the retention window, at most once a UTC day; returns how many. */
    fun prune(): Int = synchronized(newest) { recording.prune() }

    private fun record(
        code: String,
        snapshot: VenueDepth,
    ) = synchronized(newest) {
        val series = recording.series(code)
        val last = newest.getOrPut(code) { series.lastTime() ?: Long.MIN_VALUE }
        if (snapshot.timeMs <= last) return@synchronized
        series.append(listOf(snapshot.timeMs to line(snapshot)))
        newest[code] = snapshot.timeMs
    }

    /** Line [n] of [file] when its time is [within]; a line that is not `time,bids,asks` fails naming both. */
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
