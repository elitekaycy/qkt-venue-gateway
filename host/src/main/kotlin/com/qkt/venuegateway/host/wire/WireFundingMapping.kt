package com.qkt.venuegateway.host.wire

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueFundingRate
import com.qkt.vgp.WireFunding
import com.qkt.vgp.WireFundingRate

/** Funding and capabilities onto the wire. */
object WireFundingMapping {
    fun funding(f: VenueFunding): WireFunding =
        WireFunding(f.fundingId, f.symbol, f.amount.toPlainString(), f.currency, f.position?.toPlainString(), f.timeMs)

    fun rate(r: VenueFundingRate): WireFundingRate =
        WireFundingRate(r.timeMs, r.rate.toPlainString(), r.price?.toPlainString())

    /** [capabilities] as the wire names them (`funding_rates`), in a stable order. */
    fun capabilities(capabilities: Set<Capability>): List<String> = capabilities.sorted().map { it.name.lowercase() }
}
