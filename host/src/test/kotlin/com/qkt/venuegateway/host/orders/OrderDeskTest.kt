package com.qkt.venuegateway.host.orders

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

class OrderDeskTest {
    private val venue = FakeAdapter()
    private var venueUp = true

    private fun body(
        id: String = "a-1.x",
        side: String = "buy",
        quantity: String = "0.1",
        reduceOnly: Boolean = false,
    ) = WireSubmit(id, "BTC_USDC-PERPETUAL", side, "market", quantity, null, null, "gtc", reduceOnly)

    private fun desk(dir: Path) = Journal.open(dir.resolve("j.db")).let { it to OrderDesk(it, venue, { venueUp }) }

    @Test
    fun `a submit is placed once, a resend returns it, and the same id with another body is a conflict`(
        @TempDir dir: Path,
    ) {
        val (journal, desk) = desk(dir)

        val first = desk.submit(body()) as DeskResult.Ok
        val again = desk.submit(body()) as DeskResult.Ok
        val other = desk.submit(body(quantity = "0.2")) as DeskResult.Refused

        assertThat(first.created).isTrue()
        assertThat(again.created).isFalse()
        assertThat(again.order.venueOrderId).isEqualTo(first.order.venueOrderId)
        assertThat(venue.placed).containsExactly("a-1.x")
        assertThat(other.status to other.code).isEqualTo(409 to "conflict")
        assertThat(journal.eventsAfter(0).map { it.type }).containsExactly("order")
    }

    @Test
    fun `a venue refusal is a 422 and journals the rejection, a down venue is a 503 before anything is written`(
        @TempDir dir: Path,
    ) {
        val (journal, desk) = desk(dir)
        venue.refuseNext = "not enough margin"
        val refused = desk.submit(body()) as DeskResult.Refused
        venueUp = false
        val down = desk.submit(body(id = "a-2.x")) as DeskResult.Refused

        assertThat(refused.status to refused.message).isEqualTo(422 to "not enough margin")
        assertThat(journal.order("a-1.x")?.order?.status).isEqualTo("rejected")
        assertThat(down.status).isEqualTo(503)
        assertThat(journal.order("a-2.x")).isNull()
    }

    @Test
    fun `an answer lost on the way back is found by label on the resend, never placed twice`(
        @TempDir dir: Path,
    ) {
        val (_, desk) = desk(dir)
        venue.loseNextAnswer = true

        val lost = desk.submit(body()) as DeskResult.Refused
        val resent = desk.submit(body()) as DeskResult.Ok

        assertThat(lost.status).isEqualTo(503)
        assertThat(resent.order.venueOrderId).isEqualTo("v-1")
        assertThat(venue.placed).containsExactly("a-1.x")
    }

    @Test
    fun `an id the gateway never placed is written off by its lookup and can never place an order`(
        @TempDir dir: Path,
    ) {
        val (_, desk) = desk(dir)

        val lookup = desk.get("a-9.x") as DeskResult.Refused
        val late = desk.submit(body(id = "a-9.x")) as DeskResult.Refused

        assertThat(lookup.status).isEqualTo(404)
        assertThat(late.status to late.code).isEqualTo(409 to "conflict")
        assertThat(venue.placed).isEmpty()
    }

    @Test
    fun `under the kill switch only an order reducing the account passes, and cancels always do`(
        @TempDir dir: Path,
    ) {
        val (journal, desk) = desk(dir)
        venue.net["BTC_USDC-PERPETUAL"] = BigDecimal("0.3")
        desk.submit(body(id = "w-1.x")) as DeskResult.Ok
        journal.setKillSwitch(WireKillSwitch(all = true))

        val adds = desk.submit(body(id = "a-1.x")) as DeskResult.Refused
        val reduces = desk.submit(body(id = "a-2.x", side = "sell", reduceOnly = true))
        val flips =
            desk.submit(
                body(id = "a-3.x", side = "sell", quantity = "0.5", reduceOnly = true),
            ) as DeskResult.Refused
        val cancel = desk.cancel("w-1.x") as DeskResult.Ok

        assertThat(adds.status to adds.code).isEqualTo(423 to "kill_switch")
        assertThat(reduces).isInstanceOf(DeskResult.Ok::class.java)
        assertThat(flips.status).isEqualTo(423)
        assertThat(cancel.order.status).isEqualTo("cancelled")
    }
}
