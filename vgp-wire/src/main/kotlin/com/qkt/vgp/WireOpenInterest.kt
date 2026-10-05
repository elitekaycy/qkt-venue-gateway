package com.qkt.vgp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The VGP v1 open-interest objects (docs/vgp-v1-wire.md): a contract's open interest over time, as a backtest
// replays it. Figures stay decimal strings here.

/** One published figure: [openInterest] contracts outstanding in the contract's order quantity, known from [time]. */
@Serializable
data class WireOpenInterestPoint(
    val time: Long,
    @SerialName("open_interest") val openInterest: String,
)

/** `GET /v1/open-interest`: one page of [openInterest], oldest first; [next] is the next page's `from`, null on the last. */
@Serializable
data class WireOpenInterest(
    @SerialName("open_interest") val openInterest: List<WireOpenInterestPoint>,
    val next: Long? = null,
)
