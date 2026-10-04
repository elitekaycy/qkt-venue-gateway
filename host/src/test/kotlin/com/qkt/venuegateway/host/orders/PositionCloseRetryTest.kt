package com.qkt.venuegateway.host.orders

import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.vgp.WireClose
import com.qkt.vgp.WireSubmit
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `POST /v1/positions/close` with a client `client_order_id` is idempotent on it (issue #19): a retry after
 * a timeout must answer the first close, never close a second time.
 */
class PositionCloseRetryTest {
    private val venue = FakeAdapter()
    private val perp = "BTC_USDC-PERPETUAL"

    private fun closer(dir: Path) =
        OrderDesk(Journal.open(dir.resolve("j.db")), venue, { true }) { 5_000L }
            .let { it to PositionCloser(it, venue) { 5_000L } }

    private fun DeskResult.order() = (this as DeskResult.Ok).order

    private fun DeskResult.status() = (this as DeskResult.Refused).status

    @Test
    fun `a partial close retried with its id after it filled answers the same order and closes nothing more`(
        @TempDir dir: Path,
    ) {
        val (_, closer) = closer(dir)
        venue.net[perp] = BigDecimal("0.3")
        val request = WireClose(symbol = perp, quantity = "0.1", clientOrderId = "c-1")

        val first = closer.close(request).order()
        venue.net[perp] = BigDecimal("0.2")
        val retry = closer.close(request).order()

        assertThat(first.clientOrderId).isEqualTo("c-1")
        assertThat(retry.venueOrderId).isEqualTo(first.venueOrderId)
        assertThat(venue.placed).containsExactly("c-1")
    }

    @Test
    fun `a whole close retried with its id once the position is flat answers the same order, not not_found`(
        @TempDir dir: Path,
    ) {
        val (_, closer) = closer(dir)
        venue.net[perp] = BigDecimal("-0.2")
        val request = WireClose(symbol = perp, clientOrderId = "c-2")

        val first = closer.close(request).order()
        venue.net.remove(perp)
        val retry = closer.close(request).order()

        assertThat(first.side to first.quantity).isEqualTo("buy" to "0.2")
        assertThat(retry.venueOrderId).isEqualTo(first.venueOrderId)
        assertThat(venue.placed).containsExactly("c-2")
    }

    @Test
    fun `a close whose answer was lost is found on the retry and never sent twice`(
        @TempDir dir: Path,
    ) {
        val (_, closer) = closer(dir)
        venue.net[perp] = BigDecimal("0.3")
        venue.loseNextAnswer = true
        val request = WireClose(symbol = perp, clientOrderId = "c-3")

        val lost = closer.close(request).status()
        val retry = closer.close(request).order()

        assertThat(lost).isEqualTo(503)
        assertThat(retry.clientOrderId).isEqualTo("c-3")
        assertThat(venue.placed).containsExactly("c-3")
    }

    @Test
    fun `an id reused for another symbol or quantity, or one an order was sent with, is a conflict`(
        @TempDir dir: Path,
    ) {
        val (desk, closer) = closer(dir)
        venue.net[perp] = BigDecimal("0.3")
        closer.close(WireClose(symbol = perp, quantity = "0.1", clientOrderId = "c-4"))
        desk.submit(WireSubmit("o-1", perp, "sell", "limit", "0.1", "90000", null, "gtc", false))

        assertThat(closer.close(WireClose(symbol = perp, quantity = "0.2", clientOrderId = "c-4")).status())
            .isEqualTo(409)
        assertThat(closer.close(WireClose(symbol = "ETH_USDC-PERPETUAL", clientOrderId = "c-4")).status())
            .isEqualTo(409)
        assertThat(closer.close(WireClose(symbol = perp, clientOrderId = "o-1")).status()).isEqualTo(409)
        assertThat(venue.placed).containsExactly("c-4", "o-1")
    }
}
