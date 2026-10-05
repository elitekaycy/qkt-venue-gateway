package com.qkt.venuegateway.adapter

import java.math.BigDecimal

/**
 * One print of a contract's public trade tape: [size] traded at [price] at [timeMs], in the contract's order
 * quantity (the unit an order's quantity is written in). [id] is the venue's own trade id, unique per contract.
 * On the tape ([VenueAdapter.trades]) [side] is the aggressor's, the side that took liquidity: [Side.BUY] lifted
 * an offer. As a liquidation ([VenueAdapter.liquidations]) [side] is the liquidation order's: [Side.SELL] closed a
 * liquidated long, [Side.BUY] a liquidated short; a print that liquidated both its buyer and its seller is two,
 * one of each side under the same [id].
 */
data class VenuePrint(
    val id: String,
    val timeMs: Long,
    val price: BigDecimal,
    val size: BigDecimal,
    val side: Side,
)
