package com.qkt.venuegateway.host.orders

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.vgp.WireSubmit
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A lost journal file loses every record of the orders a client sent (issue #18). A client resending one
 * of them to the new journal must get the order the venue holds, never a second order.
 */
class LostJournalTest {
    private val venue = FakeAdapter()
    private val now = 50_000_000L
    private val body = WireSubmit("r-1.x", "BTC_USDC-PERPETUAL", "buy", "market", "0.3", null, null, "gtc", false)

    private fun desk(file: Path) = Journal.open(file).let { it to OrderDesk(it, venue, { true }, { now }) }

    /** Places [body] through a journal at [dir], then loses that journal file. */
    private fun placedThenJournalLost(dir: Path) {
        val file = dir.resolve("lost.db")
        val (journal, desk) = desk(file)
        desk.submit(body) as DeskResult.Ok
        journal.close()
        Files.list(dir).use { files -> files.forEach(Files::delete) }
    }

    @Test
    fun `a resend after the journal was lost answers the order the venue holds and places nothing`(
        @TempDir dir: Path,
    ) {
        placedThenJournalLost(dir)
        val (journal, desk) = desk(dir.resolve("new.db"))

        val resent = desk.submit(body) as DeskResult.Ok
        val again = desk.submit(body) as DeskResult.Ok

        assertThat(journal.createdThisRun).isTrue()
        assertThat(resent.order.venueOrderId).isEqualTo("v-1")
        assertThat(resent.created).isFalse()
        assertThat(again.order.venueOrderId).isEqualTo("v-1")
        assertThat(venue.placed).containsExactly(body.clientOrderId)
        assertThat(desk.submit(body.copy(quantity = "0.4"))).isInstanceOf(DeskResult.Refused::class.java)
    }

    @Test
    fun `a resend after the journal was lost finds an order the venue forgot by its fills`(
        @TempDir dir: Path,
    ) {
        placedThenJournalLost(dir)
        venue.orders.remove(body.clientOrderId)
        venue.fills +=
            VenueFill(
                body.clientOrderId,
                "v-1",
                "f1",
                body.symbol,
                Side.BUY,
                BigDecimal("0.3"),
                BigDecimal("84000"),
                now - 1_000,
            )
        val (journal, desk) = desk(dir.resolve("new.db"))

        val resent = desk.submit(body) as DeskResult.Ok

        assertThat(resent.order.status).isEqualTo("filled")
        assertThat(venue.placed).containsExactly(body.clientOrderId)
        assertThat(journal.fillsOf(body.clientOrderId).map { it.fillId }).containsExactly("f1")
    }

    @Test
    fun `a new journal still places an order the venue has never seen, once`(
        @TempDir dir: Path,
    ) {
        val (_, desk) = desk(dir.resolve("new.db"))

        val first = desk.submit(body) as DeskResult.Ok
        val again = desk.submit(body) as DeskResult.Ok

        assertThat(first.created).isTrue()
        assertThat(again.created).isFalse()
        assertThat(venue.placed).containsExactly(body.clientOrderId)
    }

    @Test
    fun `a journal opened again is not new, so its records are taken as complete`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("j.db")
        Journal.open(file).close()

        Journal.open(file).use { assertThat(it.createdThisRun).isFalse() }
    }
}
