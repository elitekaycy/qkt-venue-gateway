package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.VenueSettlement
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The paper account on disk (`paper.json` in the adapter's state directory), so a gateway restart finds
 * the same cash, positions and orders. Written through a temporary file moved into place; decimals are
 * kept as their exact text.
 */
class PaperStore(
    dir: Path,
) {
    private val file = dir.resolve("paper.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** Restores [book] from disk; a missing file leaves it as built. */
    fun load(book: PaperBook) {
        if (!Files.exists(file)) return
        val s = json.decodeFromString(Snapshot.serializer(), Files.readString(file))
        book.ledger.balance = BigDecimal(s.balance)
        s.positions.forEach {
            book.ledger.positions[it.symbol] =
                PaperPosition(BigDecimal(it.quantity), BigDecimal(it.avgPrice))
        }
        s.orders.forEach { book.state.orders[it.label] = it.order() }
        book.state.fills += s.fills.map { it.fill() }
        book.state.settlements += s.settlements.map { VenueSettlement(it.symbol, BigDecimal(it.price), it.timeMs) }
        book.state.triggered += s.triggered
        book.state.fillCount = s.fillCount
        book.funding.records += s.funding.map { it.funding() }
        book.funding.chargedThrough += s.fundingThrough
    }

    /** Writes [book]'s current state. */
    fun save(book: PaperBook) {
        val s =
            Snapshot(
                balance = book.ledger.balance.toPlainString(),
                positions =
                    book.ledger.positions.map { (k, p) ->
                        Position(k, p.quantity.toPlainString(), p.avgPrice.toPlainString())
                    },
                orders =
                    book.state.orders.values
                        .map(::Order),
                fills = book.state.fills.map(::Fill),
                settlements = book.state.settlements.map { Settlement(it.symbol, it.price.toPlainString(), it.timeMs) },
                triggered = book.state.triggered.toList(),
                fillCount = book.state.fillCount,
                funding = book.funding.records.map(::FundingRecord),
                fundingThrough = book.funding.chargedThrough.toMap(),
            )
        Files.createDirectories(file.parent)
        val staged = file.resolveSibling("paper.json.tmp")
        Files.writeString(staged, json.encodeToString(Snapshot.serializer(), s))
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Serializable
    private data class Snapshot(
        val balance: String,
        val positions: List<Position>,
        val orders: List<Order>,
        val fills: List<Fill>,
        val settlements: List<Settlement>,
        val triggered: List<String>,
        val fillCount: Long,
        val funding: List<FundingRecord> = emptyList(),
        val fundingThrough: Map<String, Long> = emptyMap(),
    )
}
