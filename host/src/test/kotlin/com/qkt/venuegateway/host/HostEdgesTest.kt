package com.qkt.venuegateway.host

import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.adapter.Positions
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.orders.DeskResult
import com.qkt.venuegateway.host.orders.OrderDesk
import com.qkt.venuegateway.host.orders.PositionCloser
import com.qkt.venuegateway.host.server.Role
import com.qkt.vgp.WireChange
import com.qkt.vgp.WireClose
import com.qkt.vgp.WireSubmit
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostEdgesTest {
    @TempDir lateinit var dir: Path
    private val price = BigDecimal("84000")

    private fun gateway(venue: FakeAdapter) =
        Gateway(
            venue,
            Journal.open(dir.resolve("j.db")),
            InstrumentShelf.open(dir.resolve("i.db")) { 1_000L },
            mapOf(Role.TRADER to "t"),
        ) { 1_000L }.also { it.start() }

    private fun limit(id: String) = WireSubmit(id, "BTC_USDC-PERPETUAL", "buy", "limit", "0.1", "50000", null, "gtc")

    @Test
    fun `the venue link and the quote feed each say whether quotes are live`() {
        val gateway = gateway(FakeAdapter())
        val heard = CopyOnWriteArrayList<Boolean>()
        gateway.onConnection = { heard += it }
        val link = gateway.listener

        link.connection(true, "up")
        val up = gateway.venueConnected to gateway.quotesLive
        link.quoteFeed(false, "ticker down")
        val feedDown = gateway.venueConnected to gateway.quotesLive
        link.quoteFeed(true, "ticker up")
        link.connection(false, "dropped")
        val linkDown = gateway.venueConnected to gateway.quotesLive

        assertThat(listOf(up, feedDown, linkDown)).containsExactly(true to true, true to false, false to false)
        assertThat(heard).containsExactly(true, false)
        gateway.close()
    }

    @Test
    fun `a reconciliation the venue fails is retried on the next run`() {
        val reads = AtomicInteger()
        val venue =
            object : FakeAdapter() {
                override fun openOrders() =
                    super.openOrders().also { if (reads.incrementAndGet() == 1) throw VenueUnavailableException("x") }
            }
        val gateway = gateway(venue)
        val reconciler = Reconciler(gateway, periodMs = 20)

        reconciler.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (gateway.journal.meta("reconciled_through") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        reconciler.close()

        assertThat(reads.get()).isGreaterThanOrEqualTo(2)
        assertThat(gateway.journal.meta("reconciled_through")).isEqualTo("1000")
        gateway.close()
    }

    @Test
    fun `a journal transaction that fails leaves nothing written`() {
        val journal = Journal.open(dir.resolve("j.db"))

        assertThatThrownBy {
            journal.transaction {
                journal.setMeta("half", "written")
                error("boom")
            }
        }.hasMessage("boom")

        assertThat(journal.meta("half")).isNull()
        journal.close()
    }

    @Test
    fun `a change the venue refuses is a 422 with its reason, and cancelling an order nobody knows is a 404`() {
        val venue =
            object : FakeAdapter() {
                override fun modify(
                    clientOrderId: String,
                    change: OrderChange,
                ): Boolean = throw VenueRefusedException("price out of band")
            }
        val desk = OrderDesk(Journal.open(dir.resolve("j.db")), venue, { true })
        desk.submit(limit("a-1"))

        val refused = desk.modify("a-1", WireChange(limitPrice = "1")) as DeskResult.Refused
        val unknown = desk.cancel("ghost") as DeskResult.Refused

        assertThat(listOf(refused.status, refused.code, refused.message))
            .containsExactly(422, "venue_rejected", "price out of band")
        assertThat(unknown.status to unknown.code).isEqualTo(404 to "not_found")
    }

    @Test
    fun `a close of a quantity that is not a positive decimal is refused, and an unreadable position is a 503`() {
        val venue = FakeAdapter()
        val closer = PositionCloser(OrderDesk(Journal.open(dir.resolve("j.db")), venue, { true }), venue) { 1_000L }

        val negative = closer.close(WireClose("BTC_USDC-PERPETUAL", "-1")) as DeskResult.Refused
        val word = closer.close(WireClose("BTC_USDC-PERPETUAL", "all")) as DeskResult.Refused
        venue.positionsUnavailable = true
        val down = closer.close(WireClose("BTC_USDC-PERPETUAL")) as DeskResult.Refused

        assertThat(listOf(negative, word).map { it.status to it.message })
            .containsOnly(400 to "quantity must be a positive decimal")
        assertThat(listOf(down.status, down.code, down.message))
            .containsExactly(503, "venue_unavailable", "positions down")
    }

    @Test
    fun `a close on an account holding positions by ticket is refused`() {
        val venue =
            object : FakeAdapter() {
                override fun positions() =
                    Positions(Accounting.HEDGING, listOf(PositionRow("BTC_USDC-PERPETUAL", BigDecimal.ONE, price)))
            }
        val closer = PositionCloser(OrderDesk(Journal.open(dir.resolve("j.db")), venue, { true }), venue) { 1_000L }

        val refused = closer.close(WireClose("BTC_USDC-PERPETUAL")) as DeskResult.Refused

        assertThat(refused.status to refused.message).isEqualTo(400 to "this account holds positions by ticket")
    }
}
