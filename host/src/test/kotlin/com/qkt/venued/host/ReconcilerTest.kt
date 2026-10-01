package com.qkt.venued.host

import com.qkt.venued.adapter.OrderStatus
import com.qkt.venued.adapter.Side
import com.qkt.venued.adapter.VenueFill
import com.qkt.venued.adapter.VenueSettlement
import com.qkt.venued.host.journal.Journal
import com.qkt.venued.host.server.Role
import com.qkt.vgp.WireSubmit
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReconcilerTest {
    private val venue = FakeAdapter()
    private val now = 10_000_000L

    private fun gateway(dir: Path) =
        Gateway(venue, Journal.open(dir.resolve("j.db")), mapOf(Role.TRADER to "t")) { now }.also {
            it.start()
            venue.listener!!.connection(true, "test")
        }

    private fun submit(id: String) =
        WireSubmit(id, "BTC_USDC-PERPETUAL", "buy", "market", "0.1", null, null, "gtc", false)

    private fun fill(
        id: String,
        fillId: String,
        time: Long,
    ) = VenueFill(id, "v", fillId, "BTC_USDC-PERPETUAL", Side.BUY, BigDecimal("0.1"), BigDecimal("84000"), time)

    @Test
    fun `a fill and a settlement the push missed are journaled once, and an order the venue ended is closed`(
        @TempDir dir: Path,
    ) {
        val gateway = gateway(dir)
        gateway.desk.submit(submit("a-1.x"))
        venue.orders.computeIfPresent(
            "a-1.x",
        ) { _, o -> o.copy(status = OrderStatus.FILLED, filledQuantity = o.quantity) }
        venue.fills += fill("a-1.x", "f1", now - 1_000)
        venue.settlements += VenueSettlement("BTC_USDC-27DEC26", BigDecimal("84100"), now - 500)

        val reconciler = Reconciler(gateway)
        reconciler.reconcile()
        reconciler.reconcile()

        val types = gateway.journal.eventsAfter(0).map { it.type }
        assertThat(types).containsExactly("order", "order", "fill", "settlement")
        assertThat(
            gateway.journal
                .order("a-1.x")
                ?.order
                ?.status,
        ).isEqualTo("filled")
        assertThat(gateway.journal.workingOrders()).isEmpty()
        gateway.close()
    }

    @Test
    fun `a write-ahead record the venue never received is closed as rejected, one it did receive is resolved`(
        @TempDir dir: Path,
    ) {
        val journal = Journal.open(dir.resolve("j.db"))
        journal.writeAhead(submit("lost-1.x"), "h1")
        journal.writeAhead(submit("got-1.x"), "h2")
        journal.close()
        venue.place(
            com.qkt.venued.host.wire.WireMapping
                .newOrder(submit("got-1.x")),
        )
        val gateway = gateway(dir)

        Reconciler(gateway).reconcile()

        assertThat(
            gateway.journal
                .order("lost-1.x")
                ?.order
                ?.status,
        ).isEqualTo("rejected")
        assertThat(
            gateway.journal
                .order("got-1.x")
                ?.order
                ?.status,
        ).isEqualTo("working")
        assertThat(gateway.journal.unresolved()).isEmpty()
        gateway.close()
    }
}
