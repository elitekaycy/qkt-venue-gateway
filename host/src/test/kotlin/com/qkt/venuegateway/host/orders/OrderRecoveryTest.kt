package com.qkt.venuegateway.host.orders

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.Reconciler
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.server.Role
import com.qkt.vgp.WireSubmit
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A venue forgets closed orders (Deribit answers by label for under an hour, measured on testnet
 * 2026-10-01) but keeps their trades. An order whose answer was lost must then be found by its fills,
 * never written off as unreceived, and never placed a second time.
 */
class OrderRecoveryTest {
    private val venue = FakeAdapter()
    private val now = 50_000_000L
    private val body = WireSubmit("lost-1.x", "BTC_USDC-PERPETUAL", "buy", "market", "0.3", null, null, "gtc", false)

    private fun gateway(dir: Path) =
        Gateway(
            venue,
            Journal.open(dir.resolve("j.db")),
            InstrumentShelf.open(dir.resolve("i.db")) { now },
            mapOf(
                Role.TRADER to "t",
            ),
        ) { now }
            .also {
                it.start()
                venue.listener!!.connection(true, "test")
            }

    private fun fill(
        id: String,
        quantity: String,
        price: String,
        time: Long,
    ) = VenueFill("lost-1.x", "v-1", id, "BTC_USDC-PERPETUAL", Side.BUY, BigDecimal(quantity), BigDecimal(price), time)

    /** The order reached the venue, its answer was lost, it filled, and the venue then forgot it. */
    private fun lostThenForgotten(
        gateway: Gateway,
        vararg fills: VenueFill,
    ) {
        venue.loseNextAnswer = true
        gateway.desk.submit(body)
        venue.orders.remove(body.clientOrderId)
        venue.fills += fills
    }

    @Test
    fun `the reconciler resolves a forgotten order from its fills, filled when they cover it`(
        @TempDir dir: Path,
    ) {
        val gateway = gateway(dir)
        lostThenForgotten(gateway, fill("f1", "0.1", "84000", now - 2_000), fill("f2", "0.2", "84003", now - 1_000))

        Reconciler(gateway).reconcile()

        val order = gateway.journal.order(body.clientOrderId)?.order!!
        assertThat(order.status).isEqualTo("filled")
        assertThat(order.filledQuantity).isEqualTo("0.3")
        assertThat(BigDecimal(order.avgFillPrice)).isEqualByComparingTo("84002")
        assertThat(gateway.journal.unresolved()).isEmpty()
        gateway.close()
    }

    @Test
    fun `a forgotten order filled in part ended with what filled, and one with no trace at all is written off`(
        @TempDir dir: Path,
    ) {
        val gateway = gateway(dir)
        lostThenForgotten(gateway, fill("f1", "0.1", "84000", now - 1_000))
        gateway.journal.writeAhead(body.copy(clientOrderId = "ghost-1.x"), "h")

        Reconciler(gateway).reconcile()

        val partial = gateway.journal.order(body.clientOrderId)?.order!!
        assertThat(partial.status).isEqualTo("cancelled")
        assertThat(partial.filledQuantity).isEqualTo("0.1")
        assertThat(
            gateway.journal
                .order("ghost-1.x")
                ?.order
                ?.status,
        ).isEqualTo("rejected")
        gateway.close()
    }

    @Test
    fun `a resend of a forgotten order that filled answers it filled and places nothing`(
        @TempDir dir: Path,
    ) {
        val gateway = gateway(dir)
        lostThenForgotten(gateway, fill("f1", "0.3", "84000", now - 1_000))
        val placedBefore = venue.placed.size

        val again = gateway.desk.submit(body)

        assertThat((again as DeskResult.Ok).order.status).isEqualTo("filled")
        assertThat(venue.placed).hasSize(placedBefore)
        assertThat(gateway.journal.eventsAfter(0).map { it.type }).contains("fill")
        gateway.close()
    }

    @Test
    fun `a journaled working order that ended while away is resolved from its fills, or closed when none`(
        @TempDir dir: Path,
    ) {
        val gateway = gateway(dir)
        gateway.desk.submit(body)
        gateway.desk.submit(body.copy(clientOrderId = "quiet-1.x"))
        venue.orders.remove(body.clientOrderId)
        venue.orders.remove("quiet-1.x")
        venue.fills += fill("f1", "0.3", "84000", now - 1_000)

        Reconciler(gateway).reconcile()

        assertThat(
            gateway.journal
                .order(body.clientOrderId)
                ?.order
                ?.status,
        ).isEqualTo("filled")
        assertThat(
            gateway.journal
                .order("quiet-1.x")
                ?.order
                ?.status,
        ).isEqualTo("cancelled")
        assertThat(gateway.journal.workingOrders()).isEmpty()
        gateway.close()
    }
}
