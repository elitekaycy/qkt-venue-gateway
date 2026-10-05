package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.deribit.DeribitErrors.venue
import com.qkt.venuegateway.deribit.client.DeribitTrading

/** What one Deribit account's history shows besides its fills: its expiry settlements and the funding it realized. */
internal class DeribitAccountHistory(
    private val account: DeribitTrading,
    private val currency: String,
    private val listing: DeribitListing,
) {
    /** Deliveries and exercises at the price each unit settled at ([DeribitSettlementMapping]); expired codes looked up one by one. */
    fun settlements(
        fromMs: Long,
        toMs: Long,
    ): List<VenueSettlement> =
        venue {
            val rows = account.settlements(currency, fromMs, toMs)
            DeribitSettlementMapping.settlements(rows, listing::held) { account.transactions(currency, fromMs, toMs) }
        }

    /** The funding the transaction log shows realized on perpetuals; Deribit pushes none, the host reconciles it. */
    fun funding(
        fromMs: Long,
        toMs: Long,
    ): List<VenueFunding> =
        venue { account.transactions(currency, fromMs, toMs) }.mapNotNull { row ->
            DeribitMapping.funding(row) { name -> venue { listing.held(name) }.perpetual }
        }
}
