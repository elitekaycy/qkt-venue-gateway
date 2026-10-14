package com.qkt.vgp

import kotlinx.serialization.Serializable

// The VGP v1 depth objects (docs/vgp-v1-wire.md): a contract's order book as the gateway recorded it, as a
// backtest replays it. Prices and amounts stay decimal strings here.

/**
 * One book snapshot known from [time]: [bids] from the highest price down and [asks] from the lowest up, at
 * most ten levels a side, each level `[price, amount]`.
 */
@Serializable
data class WireDepthSnapshot(
    val time: Long,
    val bids: List<List<String>>,
    val asks: List<List<String>>,
)

/** `GET /v1/depth`: one page of [depth], oldest first; [next] is the next page's `from`, null on the last. */
@Serializable
data class WireDepth(
    val depth: List<WireDepthSnapshot>,
    val next: Long? = null,
)
