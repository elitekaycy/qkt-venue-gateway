package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.Cost
import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueFill
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Alpaca takes the fee on a coin bought in the coin: 0.0002 BTC bought left 0.000199499 BTC (paper, tier 1: 0.25%
 * taker, rounded up to 9 decimals). The fill, the order and the FILL activity all carry the quantity bought, and no
 * fee; only the trade stream's `position_qty`, the position after the execution, says what was received. So the
 * position each symbol had is kept here (seeded from [positions] whenever the stream comes up, then moved by every
 * fill's `position_qty`), and a coin buy is reported as Alpaca filled it with its fee a commission in the coin: the VGP
 * rule for a spot pair's base asset, by which the client books the holding as the quantity less the fee, Alpaca's
 * position. A sale pays its fee in dollars and moves the position by the quantity sold, so it is reported as filled;
 * Alpaca reports that dollar fee nowhere per fill.
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

    /** [fill] with its coin fee, given Alpaca's `position_qty` after it ([after]); [coin] is the pair's coin, null for a share. */
    fun received(
        fill: VenueFill,
        after: BigDecimal?,
        coin: String?,
    ): VenueFill {
        if (after == null) return fill
        val before = held.put(fill.symbol, after) ?: BigDecimal.ZERO
        if (coin == null || fill.side != Side.BUY) return fill
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
        return fill.copy(costs = fill.costs + Cost(CostKind.COMMISSION, fee, coin))
    }

    private companion object {
        val MAX_FEE: BigDecimal = BigDecimal("0.01")
    }
}
