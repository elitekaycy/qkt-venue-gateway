package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.RESTING
import com.qkt.venuegateway.kalshi.client.KalshiAuth
import com.qkt.venuegateway.kalshi.client.KalshiPortfolio
import com.qkt.venuegateway.kalshi.client.KalshiPublic
import com.qkt.venuegateway.kalshi.client.KalshiRest
import java.math.BigDecimal
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Kalshi shows an amend or a cancel on the order some time after taking it (recorded on the demo: the old price
 * 0.6 s after an amend, the new one at 1 s), so a change returns only once the order read back shows it.
 */
class KalshiChangeTest {
    private val venue = KalshiVenue()
    private val slept = mutableListOf<Long>()
    private val rest = KalshiRest(venue.server.restUrl, KalshiAuth("key-id", Fixtures.pem))
    private val desk =
        KalshiOrderDesk(
            KalshiPortfolio(rest),
            KalshiListing(KalshiPublic(rest), 0, System::currentTimeMillis, listOf("KXNBA3D")),
            KalshiLabels(Files.createTempDirectory("kalshi-change")),
            { BigDecimal.ZERO },
            settleTries = 3,
            sleep = { slept += it },
        )
    private val before = "private/order-resting.json"
    private val after = "private/order-amended.json"

    /** The label's order id is known, as after the placement or a restart. */
    @BeforeEach
    fun known() {
        desk.byLabel("qkt-probe-rest")
    }

    @AfterEach
    fun stop() = venue.close()

    private fun posts() = venue.server.requests.filter { it.method == "POST" }

    private fun reads() = venue.server.requests.count { it.method == "GET" && it.url.encodedPath.endsWith(RESTING) }

    private fun twoAtTwoCents() = OrderChange(quantity = BigDecimal("2"), limitPrice = BigDecimal("0.02"))

    @Test
    fun `a change returns once the order read back shows it, not while Kalshi still shows the old one`() {
        venue.restingReads += listOf(before, before, before)

        assertThat(desk.modify("qkt-probe-rest", twoAtTwoCents())).isTrue

        assertThat(posts().single().url.encodedPath).endsWith("/$RESTING/amend")
        assertThat(slept).containsExactly(200L, 200L)
        assertThat(desk.byLabel("qkt-probe-rest")!!.limitPrice).isEqualByComparingTo("0.02")
    }

    @Test
    fun `a change Kalshi never shows is unavailable, not a success`() {
        venue.restingReads += listOf(before, before, before, before)

        assertThatThrownBy { desk.modify("qkt-probe-rest", twoAtTwoCents()) }
            .isInstanceOf(VenueUnavailableException::class.java)
            .hasMessageContaining("kalshi took the change to qkt-probe-rest but does not show it yet")
        assertThat(posts()).hasSize(1)
        assertThat(slept).containsExactly(200L, 200L)
    }

    @Test
    fun `a change to what the order already is sends nothing, as Kalshi refuses an amend that changes nothing`() {
        assertThat(desk.modify("qkt-probe-rest", twoAtTwoCents())).isTrue
        assertThat(desk.modify("qkt-probe-rest", OrderChange(limitPrice = BigDecimal("0.0200")))).isTrue
        assertThat(desk.modify("qkt-probe-rest", OrderChange())).isTrue

        assertThat(posts()).isEmpty()
        assertThat(slept).isEmpty()
    }

    @Test
    fun `a cancel returns once the order no longer rests, and is unavailable when Kalshi never shows it`() {
        venue.restingReads += listOf(after, after)

        desk.cancel("qkt-probe-rest")

        assertThat(slept).containsExactly(200L)
        assertThat(desk.byLabel("qkt-probe-rest")!!.status).isEqualTo(OrderStatus.CANCELLED)
        venue.restingReads += listOf(after, after, after, after)
        assertThatThrownBy { desk.cancel("qkt-probe-rest") }
            .isInstanceOf(VenueUnavailableException::class.java)
            .hasMessageContaining("the cancel of qkt-probe-rest")
    }
}
