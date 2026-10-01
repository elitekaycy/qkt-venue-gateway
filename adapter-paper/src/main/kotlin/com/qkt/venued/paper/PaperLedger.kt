package com.qkt.venued.paper

import com.qkt.venued.adapter.Side
import java.math.BigDecimal
import java.math.MathContext

/** One paper position: signed [quantity] (+long, −short) at an average entry [avgPrice]. */
data class PaperPosition(
    val quantity: BigDecimal,
    val avgPrice: BigDecimal,
)

/**
 * The paper account's money: cash [balance] and netting positions. A fill on the other side of a
 * position realizes `(price − entry) × closed × contract size` into cash (signed by the position); what
 * is left over opens at the fill price. Not thread-safe: the adapter calls it under one lock.
 */
class PaperLedger(
    var balance: BigDecimal,
    private val contractSizeOf: (String) -> BigDecimal,
) {
    val positions = LinkedHashMap<String, PaperPosition>()

    /** Books a fill of [quantity] at [price]. */
    fun apply(
        symbol: String,
        side: Side,
        quantity: BigDecimal,
        price: BigDecimal,
    ) {
        val size = contractSizeOf(symbol)
        val signed = if (side == Side.BUY) quantity else quantity.negate()
        val held = positions.remove(symbol) ?: PaperPosition(BigDecimal.ZERO, price)
        val opposite = held.quantity.signum() != 0 && held.quantity.signum() != signed.signum()
        val closing = if (opposite) quantity.min(held.quantity.abs()) else BigDecimal.ZERO
        if (closing.signum() > 0) {
            val direction = BigDecimal(held.quantity.signum())
            balance =
                balance.add(
                    price
                        .subtract(held.avgPrice)
                        .multiply(closing)
                        .multiply(size)
                        .multiply(direction),
                )
        }
        val next = held.quantity.add(signed)
        if (next.signum() == 0) return
        val avg =
            when {
                !opposite ->
                    held.quantity
                        .abs()
                        .multiply(
                            held.avgPrice,
                        ).add(quantity.multiply(price))
                        .divide(next.abs(), MathContext.DECIMAL128)
                next.signum() == held.quantity.signum() -> held.avgPrice
                else -> price
            }
        positions[symbol] = PaperPosition(next, avg)
    }

    /** Closes the position in [symbol] at [price] per unit; false when nothing is held. */
    fun settle(
        symbol: String,
        price: BigDecimal,
    ): Boolean {
        val held = positions.remove(symbol) ?: return false
        balance = balance.add(price.subtract(held.avgPrice).multiply(held.quantity).multiply(contractSizeOf(symbol)))
        return true
    }

    /** Cash plus every position marked at [markOf] (its entry price when unmarked). */
    fun equity(markOf: (String) -> BigDecimal?): BigDecimal =
        positions.entries.fold(balance) { sum, (symbol, p) ->
            val mark = markOf(symbol) ?: p.avgPrice
            sum.add(mark.subtract(p.avgPrice).multiply(p.quantity).multiply(contractSizeOf(symbol)))
        }

    /** Why a `reduce_only` order of [quantity] on [side] may not go, or null when it reduces without flipping. */
    fun reduceOnlyRefusal(
        symbol: String,
        side: Side,
        quantity: BigDecimal,
    ): String? {
        val held = positions[symbol]?.quantity ?: BigDecimal.ZERO
        val reduces =
            (side == Side.SELL && held.signum() > 0 || side == Side.BUY && held.signum() < 0) && quantity <= held.abs()
        return if (reduces) null else "reduce_only order would not reduce the position"
    }
}
