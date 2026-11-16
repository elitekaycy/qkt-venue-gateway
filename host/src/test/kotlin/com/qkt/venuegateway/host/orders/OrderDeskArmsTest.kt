package com.qkt.venuegateway.host.orders

import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.journal.setKillSwitch
import com.qkt.vgp.WireChange
import com.qkt.vgp.WireKillSwitch
import com.qkt.vgp.WireSubmit
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderDeskArmsTest {
    private val perp = "BTC_USDC-PERPETUAL"
    private var up = true

    private fun limit(id: String) = WireSubmit(id, perp, "buy", "limit", "0.3", "50000", null, "gtc", false)

    private fun desk(
        dir: Path,
        venue: FakeAdapter,
    ) = Journal.open(dir.resolve("j.db")).let { it to OrderDesk(it, venue, { up }) { 5_000L } }

    private fun DeskResult.refused() = this as DeskResult.Refused

    @Test
    fun `a change the venue says it did not make, or cannot find afterwards, is not found`(
        @TempDir dir: Path,
    ) {
        val forgets =
            object : FakeAdapter() {
                override fun modify(
                    clientOrderId: String,
                    change: OrderChange,
                ) = false
            }
        val (_, desk) = desk(dir, forgets)
        desk.submit(limit("l-1"))
        val vanishes =
            object : FakeAdapter() {
                override fun orderByLabel(clientOrderId: String): VenueOrder? =
                    if (modified) null else super.orderByLabel(clientOrderId)

                var modified = false

                override fun modify(
                    clientOrderId: String,
                    change: OrderChange,
                ) = super.modify(clientOrderId, change).also { modified = true }
            }
        val (_, other) = desk(dir.resolve("two").also { it.toFile().mkdirs() }, vanishes)
        other.submit(limit("l-2"))

        assertThat(desk.modify("l-1", WireChange(limitPrice = "49000")).refused().status).isEqualTo(404)
        assertThat(other.modify("l-2", WireChange(limitPrice = "49000")).refused().status).isEqualTo(404)
    }

    @Test
    fun `a change the venue refuses is a 422 with its reason, and one it cannot answer is a 503`(
        @TempDir dir: Path,
    ) {
        var failure: RuntimeException = VenueRefusedException("price outside the band")
        val venue =
            object : FakeAdapter() {
                override fun modify(
                    clientOrderId: String,
                    change: OrderChange,
                ): Boolean = throw failure
            }
        val (_, desk) = desk(dir, venue)
        desk.submit(limit("l-1"))

        val refused = desk.modify("l-1", WireChange(limitPrice = "49000")).refused()
        failure = VenueUnavailableException("venue timed out")
        val unavailable = desk.modify("l-1", WireChange(limitPrice = "49000")).refused()

        assertThat(refused.status to refused.message).isEqualTo(422 to "price outside the band")
        assertThat(unavailable.status to unavailable.message).isEqualTo(503 to "venue timed out")
    }

    @Test
    fun `a size cut that also moves the stop is no pure reduction, so the kill switch refuses it`(
        @TempDir dir: Path,
    ) {
        val (journal, desk) = desk(dir, FakeAdapter())
        desk.submit(WireSubmit("s-1", perp, "buy", "stop", "0.3", null, "60000", "gtc", false))
        journal.setKillSwitch(WireKillSwitch(all = false, symbols = listOf(perp)))

        assertThat(desk.modify("s-1", WireChange(quantity = "0.1", stopPrice = "61000")).refused().status)
            .isEqualTo(423)
        assertThat(desk.modify("s-1", WireChange(stopPrice = "61000")).refused().status).isEqualTo(423)
    }

    @Test
    fun `an order whose answer was lost has no order record to change yet`(
        @TempDir dir: Path,
    ) {
        val venue = FakeAdapter().apply { loseNextAnswer = true }
        val (_, desk) = desk(dir, venue)
        assertThat(desk.submit(limit("l-1")).refused().status).isEqualTo(503)

        assertThat(desk.modify("l-1", WireChange(limitPrice = "49000")).refused().status).isEqualTo(404)
    }

    @Test
    fun `a lookup with the venue down is a 503, and an order only the venue holds is found and journaled`(
        @TempDir dir: Path,
    ) {
        val venue = FakeAdapter()
        val (journal, desk) = desk(dir, venue)
        venue.place(
            com.qkt.venuegateway.host.wire.WireMapping
                .newOrder(limit("v-only")),
        )

        val found = desk.get("v-only") as DeskResult.Ok
        up = false
        val down = desk.get("never-seen").refused()

        assertThat(found.order.clientOrderId).isEqualTo("v-only")
        assertThat(journal.order("v-only")?.order).isNotNull
        assertThat(down.status).isEqualTo(503)
    }

    @Test
    fun `a cancel with the venue down is a 503, and one the venue no longer remembers answers the journal's order`(
        @TempDir dir: Path,
    ) {
        val forgets =
            object : FakeAdapter() {
                var forgot = false

                override fun orderByLabel(clientOrderId: String): VenueOrder? =
                    if (forgot) null else super.orderByLabel(clientOrderId)
            }
        val (_, desk) = desk(dir, forgets)
        desk.submit(limit("l-1"))

        forgets.forgot = true
        val remembered = desk.cancel("l-1") as DeskResult.Ok
        val unknown = desk.cancel("never-sent").refused()
        up = false
        val down = desk.cancel("l-1").refused()

        assertThat(remembered.order.clientOrderId).isEqualTo("l-1")
        assertThat(unknown.status).isEqualTo(404)
        assertThat(down.status).isEqualTo(503)
    }

    @Test
    fun `a place that fails for no reason the venue gave is retryable, and says the resend will resolve it`(
        @TempDir dir: Path,
    ) {
        val broken =
            object : FakeAdapter() {
                override fun place(order: com.qkt.venuegateway.adapter.NewOrder): VenueOrder = error("socket reset")
            }
        val (_, desk) = desk(dir, broken)

        val failed = desk.submit(limit("l-1")).refused()

        assertThat(failed.status).isEqualTo(503)
        assertThat(failed.message).contains("socket reset").contains("resend")
    }
}
