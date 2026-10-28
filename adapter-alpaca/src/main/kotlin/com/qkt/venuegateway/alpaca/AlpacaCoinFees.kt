package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.Cost
import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueFill
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Alpaca takes the fee on a coin bought in the coin: 0.0002 BTC bought left 0.000199499 BTC (paper, tier 1: 0.25%
 * taker, rounded up to 9 decimals). The fill, the order and the FILL activity all carry the quantity bought, and no
 * fee; only the trade stream's `position_qty`, the position after the execution, says what was received. So the
 * position each symbol had is kept here (seeded from [positions] whenever the stream comes up, then moved by every
 * fill's `position_qty`), and a coin buy is reported at the quantity received, its fee a USD commission at the fill
 * price: qkt's position then equals Alpaca's. A sale pays its fee in dollars and moves the position by the quantity
 * sold, so it is reported as filled; Alpaca reports that dollar fee nowhere per fill.
 */
class AlpacaCoinFees(
    private val positions: () -> List<PositionRow>,
) {
    private val log = LoggerFactory.getLogger(AlpacaCoinFees::class.java)
    private val held = ConcurrentHashMap<String, BigDecimal>()

    /** Reads every position from Alpaca, as the stream is (re)connected and fills since may be unknown. */
    fun seed() {
        held.clear()
        positions().forEach { held[it.symbol] = it.quantity }
    }

    /** [fill] as the position moved, given Alpaca's `position_qty` after it ([after]); [coin] when it is a coin pair. */
    fun received(
        fill: VenueFill,
        after: BigDecimal?,
        coin: Boolean,
    ): VenueFill {
        if (after == null) return fill
        val before = held.put(fill.symbol, after) ?: BigDecimal.ZERO
        if (!coin || fill.side != Side.BUY) return fill
        val received = after - before
        val fee = fill.quantity - received
        // A fee outside 0..1% of the fill means the kept position is stale (a trade made elsewhere): keep the fill as is.
        if (received.signum() <= 0 || fee.signum() < 0 || fee > fill.quantity.multiply(MAX_FEE)) {
            log.warn(
                "alpaca {} fill {}: position moved {}, not net of a fee; reported as filled",
                fill.symbol,
                fill.fillId,
                received,
            )
            return fill
        }
        if (fee.signum() == 0) return fill
        val dollars = fee.multiply(fill.price).setScale(USD_DIGITS, RoundingMode.HALF_UP)
        return fill.copy(quantity = received, costs = fill.costs + Cost(CostKind.COMMISSION, dollars, USD))
    }

    private companion object {
        val MAX_FEE: BigDecimal = BigDecimal("0.01")
        const val USD = "USD"
        const val USD_DIGITS = 8
    }
}
