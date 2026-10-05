package com.qkt.vgp

import kotlinx.serialization.Serializable

// The VGP v1 public tape objects (docs/vgp-v1-wire.md): a contract's trade prints with their aggressor side
// (`GET /v1/trades`) and the prints that liquidated a position (`GET /v1/liquidations`), in one shape.

/**
 * One print: [size] at [price] at [time]. [side] (`buy`, `sell`) is the aggressor's on the tape, and the
 * liquidation order's among liquidations (`sell` closed a liquidated long). [id] is the venue's trade id.
 */
@Serializable
data class WirePrint(
    val id: String,
    val time: Long,
    val price: String,
    val size: String,
    val side: String,
)

/** `GET /v1/trades`: one page of [trades], oldest first; [next] is the `from` of the next page, null on the last. */
@Serializable
data class WireTrades(
    val trades: List<WirePrint>,
    val next: Long? = null,
)

/** `GET /v1/liquidations`: one page of [liquidations], oldest first; [next] is the next page's `from`, null on the last. */
@Serializable
data class WireLiquidations(
    val liquidations: List<WirePrint>,
    val next: Long? = null,
)
