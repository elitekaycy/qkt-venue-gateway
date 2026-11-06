package com.qkt.venuegateway.host.wire

import com.qkt.venuegateway.adapter.EventTerms
import com.qkt.venuegateway.host.wire.WireMapping.wire
import com.qkt.vgp.WireInstrument

/** An event contract's terms onto its wire instrument; any other instrument passes through untouched. */
internal object WireEventReads {
    fun withTerms(
        w: WireInstrument,
        e: EventTerms?,
    ): WireInstrument =
        if (e == null) {
            w
        } else {
            w.copy(
                series = e.series,
                event = e.event,
                strikeType = e.strikeType.wire(),
                floor = e.floor?.toPlainString(),
                cap = e.cap?.toPlainString(),
                openAt = e.openAtMs,
                closeAt = e.closeAtMs,
                occursAt = e.occursAtMs,
                occasion = e.occasion,
                canCloseEarly = e.canCloseEarly,
                exclusive = e.exclusive,
                frequency = e.frequency,
                title = e.title,
                outcome = e.outcome?.toPlainString(),
                feeModel = e.fee?.model?.wire(),
                takerFeeRate = e.fee?.takerRate?.toPlainString(),
                makerFeeRate = e.fee?.makerRate?.toPlainString(),
                feeRounding = e.fee?.rounding?.toPlainString(),
            )
        }
}
