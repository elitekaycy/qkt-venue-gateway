package com.qkt.venuegateway.adapter

import java.io.IOException
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class VenueAdapterDefaultsTest {
    /** An adapter implementing only what every adapter must: nothing optional. */
    private object Bare : VenueAdapter {
        override val id = "bare"
        override val version = "0"
        override val klineResolutions = setOf(60_000L)

        override fun connect(listener: AdapterListener) = Unit

        override fun identity() = VenueIdentity("1", TradeMode.DEMO, "USD")

        override fun instruments() = emptyList<Instrument>()

        override fun account() = AccountSnapshot("USD", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE)

        override fun positions() = Positions(Accounting.NETTING, emptyList())

        override fun openOrders() = emptyList<VenueOrder>()

        override fun place(order: NewOrder): VenueOrder = throw VenueRefusedException("bare takes no orders")

        override fun cancel(clientOrderId: String) = Unit

        override fun modify(
            clientOrderId: String,
            change: OrderChange,
        ) = false

        override fun orderByLabel(clientOrderId: String): VenueOrder? = null

        override fun fills(
            fromMs: Long,
            toMs: Long,
        ) = emptyList<VenueFill>()

        override fun settlements(
            fromMs: Long,
            toMs: Long,
        ) = emptyList<VenueSettlement>()

        override fun klines(
            code: String,
            resolutionMs: Long,
            fromMs: Long,
            toMs: Long,
        ) = emptyList<VenueBar>()

        override fun subscribeQuotes(codes: Set<String>) = Unit

        override fun close() = Unit
    }

    @Test
    fun `an adapter that declares nothing serves the bars, quotes and settlements every v1 adapter served`() {
        assertThat(Bare.capabilities)
            .containsExactlyInAnyOrder(Capability.BARS, Capability.QUOTES, Capability.SETTLEMENTS)
    }

    @Test
    fun `every optional history an adapter does not implement is refused as unsupported, naming it`() {
        val refusals =
            mapOf<String, () -> Any>(
                "funding" to { Bare.funding(0, 1) },
                "funding rates" to { Bare.fundingRates("X", 0, 1) },
                "mark prices" to { Bare.marks("X", 60_000, 0, 1) },
                "open interest" to { Bare.openInterest("X", 0, 1) },
                "trades" to { Bare.trades("X", 0, 1, 10) },
                "liquidations" to { Bare.liquidations("X", 0, 1) },
                "depth" to { Bare.depth("X", 0, 1) },
            )

        refusals.forEach { (what, call) ->
            assertThatThrownBy { call() }
                .isInstanceOf(VenueUnsupportedException::class.java)
                .hasMessage("the adapter does not serve $what")
        }
    }

    @Test
    fun `a venue failure without a message is named by its venue and code`() {
        class Coded(
            val code: Int,
        ) : RuntimeException()

        val errors = VenueErrors("acme", { (it as? Coded)?.code }) { it >= 500 }

        assertThat(errors.translate(Coded(503)))
            .isInstanceOf(VenueUnavailableException::class.java)
            .hasMessage("acme 503")
        assertThat(errors.translate(Coded(400))).isInstanceOf(VenueRefusedException::class.java).hasMessage("acme 400")
        assertThat(errors.isVenueFailure(Coded(400))).isTrue
        assertThat(errors.isVenueFailure(IOException("reset"))).isTrue
        assertThat(errors.isVenueFailure(IllegalStateException("bug"))).isFalse
    }

    @Test
    fun `a failure that is not the venue's passes through, a checked one wrapped as unchecked`() {
        val errors = VenueErrors("acme", { null }) { false }
        val bug = IllegalArgumentException("a bug")
        val checked = Exception("checked")

        assertThat(errors.translate(bug)).isSameAs(bug)
        assertThat(errors.translate(checked)).isInstanceOf(IllegalStateException::class.java).hasCause(checked)
    }

    @Test
    fun `a refusal keeps the venue's reason and an outage keeps its cause`() {
        val cause = IOException("reset")

        assertThat(VenueRefusedException("post only").reason).isEqualTo("post only")
        assertThat(VenueUnavailableException("down", cause)).hasMessage("down").hasCause(cause)
        assertThat(VenueUnavailableException("down").cause).isNull()
    }
}
