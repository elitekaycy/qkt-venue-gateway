package com.qkt.venuegateway.host.journal

import com.qkt.vgp.WireFunding

/** Appends [funding] and its `funding` event unless its id was journaled before; true when new. */
fun Journal.appendFunding(funding: WireFunding): Boolean =
    appending {
        fundingRecords.insert(funding) &&
            records
                .event(
                    "funding",
                    funding.time,
                    json.encodeToJsonElement(WireFunding.serializer(), funding),
                ).let { true }
    }

/** Funding records from [fromMs] to [toMs], oldest first. */
fun Journal.funding(
    fromMs: Long,
    toMs: Long,
): List<WireFunding> = synchronized(this) { fundingRecords.between(fromMs, toMs) }

/** The newest journaled funding record's time; 0 before any. */
fun Journal.latestFundingTime(): Long = synchronized(this) { records.latestTime("funding") }
