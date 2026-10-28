package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.support.JsonReads.text
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Fractional shares, as Alpaca takes them (recorded on paper): fractionable assets, DAY orders, never short, $1 or more. */
class AlpacaFractionalTest {
    private fun desk(
        routes: Map<String, List<String>>,
        fractional: Boolean = true,
    ) = DeskFixture.desk(routes, fractional = fractional)

    private fun body(http: FixtureHttp) = DeskFixture.body(http)

    private fun half(tif: TimeInForce = TimeInForce.DAY) =
        NewOrder("qkt-f", "AAPL", Side.BUY, OrderType.LIMIT, BigDecimal("0.5"), BigDecimal("200.00"), null, tif)

    @Test
    fun `a fraction goes to Alpaca only on a fractionable share, on a DAY order, when the account trades fractions`() {
        val (wholeShares, _) = desk(emptyMap(), fractional = false)
        assertThatThrownBy {
            wholeShares.place(half())
        }.isInstanceOf(VenueRefusedException::class.java).hasMessageContaining("whole shares")
        val (desk, http) =
            desk(
                mapOf("POST /v2/orders" to listOf("order-fractional-limit-day.json")),
                fractional = true,
            )
        assertThat(desk.place(half()).quantity).isEqualByComparingTo("0.5")
        assertThat(body(http).text("qty")).isEqualTo("0.5")
        assertThatThrownBy { desk.place(half(TimeInForce.GTC)) }.hasMessageContaining("DAY orders only")
        assertThat(http.sent.count { it.method == "POST" }).isEqualTo(1)
    }

    @Test
    fun `what Alpaca itself refuses of a fraction is its refusal, with its reason`() {
        val cases =
            mapOf(
                "422:error-fractional-short.json" to "cannot be sold short",
                "403:error-fractional-below-dollar.json" to "minimal amount of order 1",
                "403:error-fractional-not-fractionable.json" to "not fractionable",
                "422:error-fractional-precision.json" to "1e-9",
            )
        for ((route, reason) in cases) {
            val (desk, _) = desk(mapOf("POST /v2/orders" to listOf(route)), fractional = true)
            assertThatThrownBy {
                desk.place(
                    half(),
                )
            }.isInstanceOf(VenueRefusedException::class.java).hasMessageContaining(reason)
        }
    }
}
