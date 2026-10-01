package com.qkt.venued

import com.qkt.venued.adapter.AccountSnapshot
import com.qkt.venued.adapter.Accounting
import com.qkt.venued.adapter.AdapterContext
import com.qkt.venued.adapter.AdapterListener
import com.qkt.venued.adapter.NewOrder
import com.qkt.venued.adapter.OrderChange
import com.qkt.venued.adapter.Positions
import com.qkt.venued.adapter.TradeMode
import com.qkt.venued.adapter.VenueAdapter
import com.qkt.venued.adapter.VenueAdapterFactory
import com.qkt.venued.adapter.VenueIdentity
import com.qkt.venued.adapter.VenueRefusedException
import java.math.BigDecimal

/** A plugin as a third party would write one: registered only from a jar in the plugins directory. */
class TestVenueFactory : VenueAdapterFactory {
    override val type = "test-venue"

    override fun create(context: AdapterContext): VenueAdapter =
        object : VenueAdapter {
            override val id = "test-venue"
            override val version = "9"

            override fun connect(listener: AdapterListener) = listener.connection(true, "test venue up")

            override fun identity() = VenueIdentity(context.required("login"), TradeMode.DEMO, "USDC")

            override fun instruments() = emptyList<com.qkt.venued.adapter.Instrument>()

            override fun account() =
                AccountSnapshot("USDC", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE)

            override fun positions() = Positions(Accounting.NETTING, emptyList())

            override fun openOrders() = emptyList<com.qkt.venued.adapter.VenueOrder>()

            override fun place(order: NewOrder) = throw VenueRefusedException("test venue takes no orders")

            override fun cancel(clientOrderId: String) = null

            override fun modify(
                clientOrderId: String,
                change: OrderChange,
            ) = null

            override fun orderByLabel(clientOrderId: String) = null

            override fun fills(
                fromMs: Long,
                toMs: Long,
            ) = emptyList<com.qkt.venued.adapter.VenueFill>()

            override fun settlements(
                fromMs: Long,
                toMs: Long,
            ) = emptyList<com.qkt.venued.adapter.VenueSettlement>()

            override fun bars(
                code: String,
                windowMs: Long,
                fromMs: Long,
                toMs: Long,
            ) = emptyList<com.qkt.venued.adapter.VenueBar>()

            override fun subscribeQuotes(
                codes: Set<String>,
                roots: Set<String>,
            ) {}

            override fun close() {}
        }
}
