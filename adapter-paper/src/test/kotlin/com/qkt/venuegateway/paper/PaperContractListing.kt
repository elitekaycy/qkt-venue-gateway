package com.qkt.venuegateway.paper

import com.qkt.venuegateway.deribit.client.DeribitInstrument
import java.math.BigDecimal

/** A listed USDC contract as Deribit describes it: a perpetual without [expiryMs], a put when it has a [strike]. */
internal fun contractListed(
    name: String,
    expiryMs: Long?,
    strike: String?,
) = DeribitInstrument(
    name,
    if (strike == null) "future" else "option",
    expiryMs == null,
    expiryMs,
    strike?.let(::BigDecimal),
    strike?.let { "put" },
    BigDecimal.ONE,
    BigDecimal("0.5"),
    BigDecimal("0.01"),
    "USDC",
    "btc_usdc",
)
