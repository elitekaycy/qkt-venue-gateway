package com.qkt.venuegateway.host.orders

import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.vgp.WireChange
import com.qkt.vgp.WireClose
import com.qkt.vgp.WireKillSwitch
import com.qkt.vgp.WireSubmit
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `PATCH /v1/orders/{id}` and `POST /v1/positions/close` (wire spec §3) at the order desk. */
class OrderChangesTest {
    private val venue = FakeAdapter()
    private var up = true
    private val perp = "BTC_USDC-PERPETUAL"

    private fun desk(dir: Path) =
        Journal.open(dir.resolve("j.db")).let { it to OrderDesk(it, venue, { up }) { 5_000L } }

    private fun limit(id: String) = WireSubmit(id, perp, "buy", "limit", "0.3", "50000", null, "gtc", false)

    private fun DeskResult.order() = (this as DeskResult.Ok).order

    private fun DeskResult.status() = (this as DeskResult.Refused).status

    @Test
    fun `a change reaches the venue and is journaled, an unknown order is not found, and an empty change is refused`(
        @TempDir dir: Path,
    ) {
        val (journal, desk) = desk(dir)
        desk.submit(limit("l-1"))

        val changed = desk.modify("l-1", WireChange(limitPrice = "49000"))

        assertThat(changed.order().limitPrice).isEqualTo("49000")
        assertThat(journal.order("l-1")?.order?.limitPrice).isEqualTo("49000")
        assertThat(desk.modify("never-sent", WireChange(limitPrice = "1")).status()).isEqualTo(404)
        assertThat(desk.modify("l-1", WireChange()).status()).isEqualTo(400)
        assertThat(desk.modify("l-1", WireChange(quantity = "-1")).status()).isEqualTo(400)
        up = false
        assertThat(desk.modify("l-1", WireChange(limitPrice = "48000")).status()).isEqualTo(503)
    }

    @Test
    fun `under the kill switch only a pure size reduction passes`(
        @TempDir dir: Path,
    ) {
        val (journal, desk) = desk(dir)
        desk.submit(limit("l-1"))
        journal.setKillSwitch(WireKillSwitch(all = false, symbols = listOf(perp)))

        assertThat(desk.modify("l-1", WireChange(limitPrice = "49000")).status()).isEqualTo(423)
        assertThat(desk.modify("l-1", WireChange(quantity = "0.5")).status()).isEqualTo(423)
        assertThat(desk.modify("l-1", WireChange(quantity = "0.1", limitPrice = "49000")).status()).isEqualTo(423)
        assertThat(desk.modify("l-1", WireChange(quantity = "0.1")).order().quantity).isEqualTo("0.1")
    }

    @Test
    fun `a close sends a reduce-only market order for the position, whole or part, through the kill switch`(
        @TempDir dir: Path,
    ) {
        val (journal, desk) = desk(dir)
        val closer = PositionCloser(desk, venue) { 5_000L }
        venue.net[perp] = BigDecimal("0.3")
        journal.setKillSwitch(WireKillSwitch(all = true, symbols = emptyList()))

        val part = closer.close(WireClose(symbol = perp, quantity = "0.1")).order()
        val rest = closer.close(WireClose(symbol = perp)).order()

        assertThat(part.side).isEqualTo("sell")
        assertThat(part.type).isEqualTo("market")
        assertThat(part.quantity).isEqualTo("0.1")
        assertThat(part.reduceOnly).isTrue()
        assertThat(rest.quantity).isEqualTo("0.3")
        assertThat(part.clientOrderId).isNotEqualTo(rest.clientOrderId)
        assertThat(journal.order(part.clientOrderId)?.order).isEqualTo(part)
    }

    @Test
    fun `a close of nothing held, of more than is held, by ticket on a netting account, or while down is refused`(
        @TempDir dir: Path,
    ) {
        val (_, desk) = desk(dir)
        val closer = PositionCloser(desk, venue) { 5_000L }
        venue.net[perp] = BigDecimal("-0.2")

        assertThat(closer.close(WireClose(symbol = "BTC_USDC-27DEC26")).status()).isEqualTo(404)
        assertThat(closer.close(WireClose(symbol = perp, quantity = "0.3")).status()).isEqualTo(400)
        assertThat(closer.close(WireClose(ticket = "123")).status()).isEqualTo(400)
        assertThat(closer.close(WireClose()).status()).isEqualTo(400)
        assertThat(closer.close(WireClose(symbol = perp)).order().side).isEqualTo("buy")
        up = false
        assertThat(closer.close(WireClose(symbol = perp)).status()).isEqualTo(503)
    }
}
