package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.deribit.DeribitTicker
import java.math.BigDecimal

/** The price a [side] order trades at on [ticker]: the ask to buy, the bid to sell; null when that side is not quoted. */
internal fun touch(
    side: Side,
    ticker: DeribitTicker?,
): BigDecimal? = ticker?.let { if (side == Side.BUY) it.ask else it.bid }

/** Whether the [touch] has reached a stop at [stop]: at or above it to buy, at or below it to sell. */
internal fun crossed(
    side: Side,
    touch: BigDecimal,
    stop: BigDecimal,
): Boolean = if (side == Side.BUY) touch >= stop else touch <= stop

/** Whether a limit at [limit] can trade at the [touch]: at or below it to buy, at or above it to sell. */
internal fun marketable(
    side: Side,
    touch: BigDecimal,
    limit: BigDecimal,
): Boolean = if (side == Side.BUY) touch <= limit else touch >= limit
