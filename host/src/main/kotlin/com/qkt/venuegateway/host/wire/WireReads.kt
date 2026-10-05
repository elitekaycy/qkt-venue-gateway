package com.qkt.venuegateway.host.wire

import com.qkt.venuegateway.adapter.AccountSnapshot
import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.Positions
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueMark
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.host.wire.WireMapping.wire
import com.qkt.vgp.WireAccount
import com.qkt.vgp.WireBar
import com.qkt.vgp.WireInstrument
import com.qkt.vgp.WireMark
import com.qkt.vgp.WirePosition
import com.qkt.vgp.WirePositions
import com.qkt.vgp.WireQuote

/** The read endpoints' translation of the adapters' account, listing and positions onto the wire. */
object WireReads {
    fun account(a: AccountSnapshot): WireAccount =
        WireAccount(
            a.currency,
            a.balance.toPlainString(),
            a.equity.toPlainString(),
            a.marginUsed.toPlainString(),
            a.marginAvailable.toPlainString(),
            a.initialMargin?.toPlainString(),
            a.maintenanceMargin?.toPlainString(),
        )

    fun instrument(i: Instrument): WireInstrument =
        WireInstrument(
            code = i.code,
            kind = i.kind.wire(),
            currency = i.currency,
            contractSize = i.contractSize.toPlainString(),
            tickSize = i.tickSize.toPlainString(),
            volumeStep = i.volumeStep.toPlainString(),
            volumeMin = i.volumeMin.toPlainString(),
            expiry = i.expiryMs,
            strike = i.strike?.toPlainString(),
            right = i.right,
            underlying = i.underlying,
        )

    fun positions(p: Positions): WirePositions =
        WirePositions(
            p.accounting.wire(),
            p.rows.map {
                WirePosition(
                    it.symbol,
                    it.quantity.toPlainString(),
                    it.avgPrice.toPlainString(),
                    it.ticket,
                    it.openedAtMs,
                )
            },
        )

    fun quote(q: VenueQuote): WireQuote =
        WireQuote(
            q.symbol,
            q.bid?.toPlainString(),
            q.ask?.toPlainString(),
            q.bidSize?.toPlainString(),
            q.askSize?.toPlainString(),
            q.mark?.toPlainString(),
            q.markIv?.toPlainString(),
            q.underlying?.toPlainString(),
            q.timeMs,
            q.index?.toPlainString(),
        )

    fun bar(b: VenueBar): WireBar =
        WireBar(
            b.startMs,
            b.open.toPlainString(),
            b.high.toPlainString(),
            b.low.toPlainString(),
            b.close.toPlainString(),
            b.volume.toPlainString(),
        )

    fun mark(m: VenueMark): WireMark = WireMark(m.timeMs, m.mark?.toPlainString(), m.index?.toPlainString())
}
