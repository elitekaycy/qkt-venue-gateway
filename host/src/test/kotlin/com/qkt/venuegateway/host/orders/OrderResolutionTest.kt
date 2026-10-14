package com.qkt.venuegateway.host.orders

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.journal.setKillSwitch
import com.qkt.vgp.WireKillSwitch
import com.qkt.vgp.WireSubmit
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Orders whose venue answer was lost or overtaken: resolved from the venue, never placed twice, never written off early. */
class OrderResolutionTest {
    private val venue = FakeAdapter()
    private var now = 1_000_000L
    private val minute = 60_000L

    private fun body(id: String = "a-1.x") =
        WireSubmit(id, "BTC_USDC-PERPETUAL", "buy", "market", "0.1", null, null, "gtc", false)

    private fun desk(dir: Path) = Journal.open(dir.resolve("j.db")).let { it to OrderDesk(it, venue, { true }) { now } }

    @Test
    fun `an older order state arriving after a newer one is dropped, so a filled order never reads working again`(
        @TempDir dir: Path,
    ) {
        val (journal, desk) = desk(dir)
        val working = (desk.submit(body()) as DeskResult.Ok).order
        val filled = working.copy(status = "filled", filledQuantity = "0.1", updatedAt = working.updatedAt + 1)

        assertThat(journal.appendOrder(filled)).isTrue()
        assertThat(journal.appendOrder(working)).isFalse()
        assertThat(journal.appendOrder(filled.copy(filledQuantity = "0.05"))).isFalse()
        assertThat((desk.get("a-1.x") as DeskResult.Ok).order.status).isEqualTo("filled")
    }

    @Test
    fun `a resend of an order the venue already holds is recovered under the kill switch, not refused`(
        @TempDir dir: Path,
    ) {
        val (journal, desk) = desk(dir)
        venue.loseNextAnswer = true
        desk.submit(body())
        journal.setKillSwitch(WireKillSwitch(all = true))

        val resent = desk.submit(body()) as DeskResult.Ok

        assertThat(resent.order.venueOrderId).isEqualTo("v-1")
        assertThat(venue.placed).containsExactly("a-1.x")
    }

    @Test
    fun `a submit that never reached the venue is neither placed again nor written off until its grace has passed`(
        @TempDir dir: Path,
    ) {
        val (journal, desk) = desk(dir)
        venue.unreachable = true
        desk.submit(body())
        venue.unreachable = false

        val resent = desk.submit(body()) as DeskResult.Refused
        val looked = desk.get("a-1.x") as DeskResult.Refused
        val reconciled = desk.resolve(body())
        now += 3 * minute
        val placed = desk.submit(body()) as DeskResult.Ok

        assertThat(resent.status to looked.status).isEqualTo(503 to 503)
        assertThat(reconciled).isNull()
        assertThat(journal.isDead("a-1.x")).isFalse()
        assertThat(placed.created).isTrue()
        assertThat(venue.placed).containsExactly("a-1.x")
    }

    @Test
    fun `a lookup of a write-ahead whose label the venue forgot is rebuilt from its fills, not written off`(
        @TempDir dir: Path,
    ) {
        val (journal, desk) = desk(dir)
        venue.loseNextAnswer = true
        desk.submit(body())
        venue.orders.remove("a-1.x")
        venue.fills +=
            VenueFill(
                "a-1.x",
                "v-1",
                "f-1",
                "BTC_USDC-PERPETUAL",
                Side.BUY,
                BigDecimal("0.1"),
                BigDecimal("84000"),
                now,
            )
        now += 3 * minute

        val found = desk.get("a-1.x") as DeskResult.Ok

        assertThat(found.order.status to found.order.filledQuantity).isEqualTo("filled" to "0.1")
        assertThat(journal.fillsOf("a-1.x").map { it.fillId }).containsExactly("f-1")
        assertThat(journal.isDead("a-1.x")).isFalse()
    }

    @Test
    fun `the reconciler rejects a settled write-ahead the venue holds no trace of`(
        @TempDir dir: Path,
    ) {
        val (journal, desk) = desk(dir)
        venue.unreachable = true
        desk.submit(body())
        venue.unreachable = false
        now += 3 * minute

        assertThat(desk.resolve(body())?.status).isEqualTo("rejected")
        assertThat(journal.order("a-1.x")?.order?.status).isEqualTo("rejected")
    }

    @Test
    fun `a cancel of an order the venue has forgotten answers the state the journal holds`(
        @TempDir dir: Path,
    ) {
        val (journal, desk) = desk(dir)
        val placed = (desk.submit(body()) as DeskResult.Ok).order
        journal.appendOrder(placed.copy(status = "filled", filledQuantity = "0.1", updatedAt = placed.updatedAt + 1))
        venue.orders.remove("a-1.x")

        val cancelled = desk.cancel("a-1.x") as DeskResult.Ok

        assertThat(cancelled.order.status).isEqualTo("filled")
    }
}
