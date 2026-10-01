package com.qkt.venued.host.wire

import com.qkt.venued.adapter.AccountSnapshot
import com.qkt.venued.adapter.Instrument
import com.qkt.venued.adapter.Positions
import com.qkt.venued.host.wire.WireMapping.wire
import com.qkt.vgp.WireAccount
import com.qkt.vgp.WireInstrument
import com.qkt.vgp.WirePosition
import com.qkt.vgp.WirePositions

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
}
