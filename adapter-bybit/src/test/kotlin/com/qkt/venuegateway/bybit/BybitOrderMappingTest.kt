package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.bybit.client.BybitPrivateJson
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitOrderMappingTest {
    private val linear =
        BybitSettings.of(
            mapOf(
                "environment" to "testnet",
                "category" to "linear",
                "stop_trigger" to "mark_price",
            ),
        )
    private val spot = BybitSettings.of(mapOf("environment" to "testnet", "category" to "spot"))

    private fun order(
        type: OrderType,
        side: Side = Side.BUY,
        id: String = "dsl-bb_perp--4.mg8q2kv1",
        tif: TimeInForce = TimeInForce.GTC,
        reduceOnly: Boolean = false,
    ) = NewOrder(
        id,
        "BTCUSDT",
        side,
        type,
        BigDecimal("0.001"),
        BigDecimal("80000"),
        BigDecimal("90000"),
        tif,
        reduceOnly,
    )

    private fun body(
        o: NewOrder,
        settings: BybitSettings = linear,
        index: Int? = 0,
    ) = BybitPrivateJson.create(BybitOrderMapping.newOrder(o, settings, index)).toString()

    @Test
    fun `a linear limit goes with its price, its label as orderLinkId and its position index`() {
        assertThat(body(order(OrderType.LIMIT))).isEqualTo(
            """{"category":"linear","symbol":"BTCUSDT","side":"Buy","orderType":"Limit","qty":"0.001",""" +
                """"price":"80000","timeInForce":"GTC","orderLinkId":"dsl-bb_perp--4.mg8q2kv1","positionIdx":0}""",
        )
    }

    @Test
    fun `a market close is reduce-only with no price`() {
        assertThat(body(order(OrderType.MARKET, Side.SELL, reduceOnly = true)))
            .contains("\"orderType\":\"Market\"", "\"reduceOnly\":true")
            .doesNotContain("price")
    }

    @Test
    fun `a buy stop fires as the price rises to it and a sell stop as it falls, on the configured trigger`() {
        val buy = body(order(OrderType.STOP))
        val sell = body(order(OrderType.STOP_LIMIT, Side.SELL))

        assertThat(
            buy,
        ).contains(
            "\"orderType\":\"Market\"",
            "\"triggerPrice\":\"90000\"",
            "\"triggerDirection\":1",
            "\"triggerBy\":\"MarkPrice\"",
        )
        assertThat(buy).doesNotContain("\"price\"")
        assertThat(sell).contains("\"orderType\":\"Limit\"", "\"price\":\"80000\"", "\"triggerDirection\":2")
    }

    @Test
    fun `a spot market order counts its quantity in the coin and a spot stop is a stop order`() {
        val market = body(order(OrderType.MARKET, reduceOnly = true), spot, null)
        val stop = body(order(OrderType.STOP), spot, null)

        assertThat(
            market,
        ).contains("\"category\":\"spot\"", "\"marketUnit\":\"baseCoin\"").doesNotContain("reduceOnly", "positionIdx")
        assertThat(
            stop,
        ).contains(
            "\"orderFilter\":\"StopOrder\"",
            "\"triggerPrice\":\"90000\"",
        ).doesNotContain("triggerDirection", "triggerBy")
        assertThat(body(order(OrderType.LIMIT), spot, null)).doesNotContain("marketUnit")
    }

    @Test
    fun `a day order and a label longer than bybit takes are refused before sending`() {
        assertThatThrownBy {
            body(order(OrderType.LIMIT, tif = TimeInForce.DAY))
        }.isInstanceOf(VenueRefusedException::class.java)
        assertThatThrownBy { body(order(OrderType.LIMIT, id = "x".repeat(46))) }
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("45")
        assertThat(body(order(OrderType.LIMIT, id = "x".repeat(45)))).contains("x".repeat(45))
    }
}
