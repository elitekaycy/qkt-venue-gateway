package com.qkt.venued.paper

import com.qkt.venued.adapter.Cost
import com.qkt.venued.adapter.CostKind
import com.qkt.venued.adapter.OrderStatus
import com.qkt.venued.adapter.OrderType
import com.qkt.venued.adapter.Side
import com.qkt.venued.adapter.TimeInForce
import com.qkt.venued.adapter.VenueFill
import com.qkt.venued.adapter.VenueOrder
import com.qkt.venued.adapter.VenueSettlement
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
    )

    @Serializable
    private data class Position(
        val symbol: String,
        val quantity: String,
        val avgPrice: String,
    )

    @Serializable
    private data class Settlement(
        val symbol: String,
        val price: String,
        val timeMs: Long,
    )

    @Serializable
    private data class Order(
        val label: String,
        val venueId: String?,
        val symbol: String,
        val side: String,
        val type: String,
        val quantity: String,
        val limit: String?,
        val stop: String?,
        val tif: String,
        val reduceOnly: Boolean,
        val status: String,
        val filled: String,
        val avgFill: String?,
        val reason: String?,
        val createdMs: Long,
        val updatedMs: Long,
    ) {
        constructor(o: VenueOrder) : this(
            o.clientOrderId,
            o.venueOrderId,
            o.symbol,
            o.side.name,
            o.type.name,
            o.quantity.toPlainString(),
            o.limitPrice?.toPlainString(),
            o.stopPrice?.toPlainString(),
            o.timeInForce.name,
            o.reduceOnly,
            o.status.name,
            o.filledQuantity.toPlainString(),
            o.avgFillPrice?.toPlainString(),
            o.rejectReason,
            o.createdAtMs,
            o.updatedAtMs,
        )

        fun order() =
            VenueOrder(
                label,
                venueId,
                symbol,
                Side.valueOf(side),
                OrderType.valueOf(type),
                BigDecimal(quantity),
                limit?.let(::BigDecimal),
                stop?.let(::BigDecimal),
                TimeInForce.valueOf(tif),
                reduceOnly,
                OrderStatus.valueOf(status),
                BigDecimal(filled),
                avgFill?.let(::BigDecimal),
                reason,
                createdMs,
                updatedMs,
            )
    }

    @Serializable
    private data class Fill(
        val label: String,
        val venueId: String?,
        val fillId: String,
        val symbol: String,
        val side: String,
        val quantity: String,
        val price: String,
        val timeMs: Long,
        val costs: List<Charge>,
    ) {
        constructor(f: VenueFill) : this(
            f.clientOrderId,
            f.venueOrderId,
            f.fillId,
            f.symbol,
            f.side.name,
            f.quantity.toPlainString(),
            f.price.toPlainString(),
            f.timeMs,
            f.costs.map { Charge(it.kind.name, it.amount.toPlainString(), it.currency) },
        )

        fun fill() =
            VenueFill(
                label,
                venueId,
                fillId,
                symbol,
                Side.valueOf(side),
                BigDecimal(quantity),
                BigDecimal(price),
                timeMs,
                costs.map { Cost(CostKind.valueOf(it.kind), BigDecimal(it.amount), it.currency) },
            )
    }

    @Serializable
    private data class Charge(
        val kind: String,
        val amount: String,
        val currency: String,
    )
}
