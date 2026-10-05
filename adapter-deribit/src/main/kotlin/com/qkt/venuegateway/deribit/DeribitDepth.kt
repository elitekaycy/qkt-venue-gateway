package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueLevel
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.deribit.DeribitErrors.venue
import com.qkt.venuegateway.deribit.client.DeribitOrderBook
import java.math.BigDecimal
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

/**
 * Deribit's order book as this gateway recorded it. Deribit publishes no book history, so a read that reaches
 * the present ([PRESENT_MS]) first takes the book as it stands ([book]: `public/get_order_book` of a code, its
 * best [VenueDepth.MAX_LEVELS] levels a side) and records it at Deribit's own stamp, then serves what was
 * recorded in the window. The record is one file a UTC day under [stateDir], `depth/<code>/<yyyy-MM-dd>.csv`
 * (`time,bids,asks`, each side `price:amount` levels best first, space separated), kept across restarts. A
 * series starts when the gateway first read it and holds one snapshot per read: a gap where nothing read it
 * stays a gap. Shared by every adapter quoting Deribit's public market.
 */
class DeribitDepth(
    private val book: (code: String, levels: Int) -> DeribitOrderBook,
    private val stateDir: Path,
    private val clock: () -> Long,
) {
    private val newest = HashMap<String, Long>()
    private val log = LoggerFactory.getLogger(DeribitDepth::class.java)

    /** [code]'s recorded snapshots from [fromMs] to [toMs], oldest first, after recording the present one when the window reaches it. */
    fun read(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueDepth> {
        if (!CODE.matches(code)) throw VenueRefusedException("$code is not a Deribit instrument name")
        if (toMs >=
            clock() - PRESENT_MS
        ) {
            record(code, DeribitMarketMapping.depth(venue { book(code, VenueDepth.MAX_LEVELS) }))
        }
        if (fromMs > toMs) return emptyList()
        synchronized(newest) {
            return days(code, day(fromMs), day(toMs)).flatMap { load(it, fromMs, toMs) }
        }
    }

    private fun record(
        code: String,
        snapshot: VenueDepth,
    ) = synchronized(newest) {
        val last =
            newest.getOrPut(
                code,
            ) { days(code, LocalDate.MIN, LocalDate.MAX).lastOrNull()?.let(::lastTime) ?: -1 }
        if (snapshot.timeMs <= last) return@synchronized
        val file = dir(code).resolve("${day(snapshot.timeMs)}.csv")
        Files.createDirectories(file.parent)
        if (!Files.exists(file) || Files.size(file) == 0L) Files.writeString(file, HEADER) else cutTornLine(file)
        Files.writeString(file, line(snapshot), StandardOpenOption.APPEND)
        newest[code] = snapshot.timeMs
    }

    /** [code]'s day files from [first] to [last], oldest first. */
    private fun days(
        code: String,
        first: LocalDate,
        last: LocalDate,
    ): List<Path> {
        val dir = dir(code)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { files ->
            files
                .filter { DAY_FILE.matches(it.fileName.toString()) }
                .filter { LocalDate.parse(it.fileName.toString().removeSuffix(".csv")) in first..last }
                .sorted()
                .toList()
        }
    }

    /**
     * The snapshots in [file] from [fromMs] to [toMs]. A last line without its newline is an append a crash cut
     * short, left out here and cut off before the next append; any complete line that is not
     * `time,bids,asks` fails naming the file and line, as a record no one can trust.
     */
    private fun load(
        file: Path,
        fromMs: Long,
        toMs: Long,
    ): List<VenueDepth> =
        complete(file).lines().drop(1).withIndex().filter { it.value.isNotBlank() }.mapNotNull { (i, line) ->
            val cells = line.split(',')
            val time = cells[0].toLongOrNull()
            require(cells.size == 3 && time != null) { "$file line ${i + 2}: expected time,bids,asks: $line" }
            if (time !in fromMs..toMs) return@mapNotNull null
            val parsed = runCatching { VenueDepth(time, levels(cells[1]), levels(cells[2])) }
            parsed.getOrElse { throw IllegalArgumentException("$file line ${i + 2}: unreadable levels: $line", it) }
        }

    private fun lastTime(file: Path): Long? =
        complete(file)
            .lines()
            .lastOrNull { it.isNotBlank() && it != HEADER.trim() }
            ?.substringBefore(',')
            ?.toLong()

    private fun complete(file: Path): String {
        val text = Files.readString(file)
        return text.substring(0, text.lastIndexOf('\n') + 1)
    }

    private fun cutTornLine(file: Path) {
        val bytes = Files.readAllBytes(file)
        val complete = bytes.lastIndexOf('\n'.code.toByte()) + 1
        if (complete == bytes.size) return
        log.warn("{}: dropping a torn last line (an append cut short)", file)
        FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(complete.toLong()) }
    }

    private fun dir(code: String): Path = stateDir.resolve("depth").resolve(code)

    private companion object {
        /** How close to now a window must end for a read to record the present book first. */
        const val PRESENT_MS = 60_000L
        const val HEADER = "time,bids,asks\n"
        val CODE = Regex("[A-Za-z0-9_-]+")
        val DAY_FILE = Regex("""\d{4}-\d{2}-\d{2}\.csv""")

        fun day(ms: Long): LocalDate = LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC)

        fun line(d: VenueDepth) = "${d.timeMs},${side(d.bids)},${side(d.asks)}\n"

        fun side(levels: List<VenueLevel>) =
            levels.joinToString(" ") { "${it.price.toPlainString()}:${it.amount.toPlainString()}" }

        fun levels(cell: String): List<VenueLevel> =
            cell.split(' ').filter { it.isNotEmpty() }.map {
                val (price, amount) = it.split(':')
                VenueLevel(BigDecimal(price), BigDecimal(amount))
            }
    }
}
