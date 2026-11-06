package com.qkt.venuegateway.host.orders

import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.vgp.WireChange
import com.qkt.vgp.WireSubmit
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderDeskChangeInputsTest {
    private val perp = "BTC_USDC-PERPETUAL"

    private fun limit(id: String) = WireSubmit(id, perp, "buy", "limit", "0.3", "50000", null, "gtc", false)

    private fun DeskResult.refused() = this as DeskResult.Refused

    @Test
    fun `a change field that is not a positive decimal is a bad request naming the change`(
        @TempDir dir: Path,
    ) {
        val desk = Journal.open(dir.resolve("j.db")).let { OrderDesk(it, FakeAdapter(), { true }) { 1L } }
        desk.submit(limit("l-1"))

        listOf("abc", "0", "-1", "").forEach {
            val refused = desk.modify("l-1", WireChange(limitPrice = it)).refused()
            assertThat(refused.status).describedAs(it).isEqualTo(400)
            assertThat(refused.code).isEqualTo("invalid_request")
        }
        assertThat(desk.modify("l-1", WireChange(quantity = "0.2", stopPrice = "abc")).refused().status).isEqualTo(400)
    }

    @Test
    fun `an order the venue has forgotten and the journal never recorded is not found when cancelled`(
        @TempDir dir: Path,
    ) {
        val venue =
            object : FakeAdapter() {
                override fun orderByLabel(clientOrderId: String): VenueOrder? = null
            }.apply { loseNextAnswer = true }
        val desk = Journal.open(dir.resolve("j.db")).let { OrderDesk(it, venue, { true }) { 1L } }
        assertThat(desk.submit(limit("l-1")).refused().status).isEqualTo(503)

        assertThat(desk.cancel("l-1").refused().status).isEqualTo(404)
    }
}
