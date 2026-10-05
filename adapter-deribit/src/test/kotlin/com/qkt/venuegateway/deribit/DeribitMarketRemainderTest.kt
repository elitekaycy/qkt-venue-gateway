package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.deribit.client.DeribitException
import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitOrder
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A market order, or a fired stop-market, Deribit left working with a remainder (testnet recordings of
 * 2026-10-05: the remainder rests as a limit at the price-band edge) is cancelled and reported as it stands.
 */
class DeribitMarketRemainderTest {
    private fun fixture(name: String) =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/private/$name")!!.readText()).jsonObject

    private fun result(name: String) = fixture(name)["response"]!!.jsonObject["result"]!!

    private fun order(json: JsonElement) = DeribitPrivateJson.order(json.jsonObject)

    private fun pushed(name: String) =
        fixture(name)["notifications"]!!.jsonArray.map { order(it.jsonObject["params"]!!.jsonObject["data"]!!) }

    private val placed = order(result("buy-market-remainder.json").jsonObject["order"]!!)
    private val cancelled = order(result("cancel-market-remainder.json"))
    private val refusal =
        fixture("cancel-market-remainder-again.json")["response"]!!.jsonObject["error"]!!.jsonObject.let {
            DeribitException(it["code"]!!.jsonPrimitive.int, it["message"]!!.jsonPrimitive.content)
        }
    private val deribit = ScriptedDeribit(placed)

    private val listed =
        Json
            .parseToJsonElement(javaClass.getResource("/fixtures/instruments-future.json")!!.readText())
            .jsonObject["result"]!!
            .jsonArray
            .map { DeribitJson.instrument(it.jsonObject) }

    /** A market listing the recorded USDC futures, so an order passes the listing guard. */
    private val market =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(DeribitMarketData::class.java),
        ) { _, m, a ->
            if (m.name == "instruments" && a[1] == "future") listed else emptyList<Any>()
        } as DeribitMarketData

    private fun adapter(dir: Path) = deribit.adapter(dir, market)

    /** A market buy of [order]'s size on a listed contract: the scripted account answers [order] whatever is sent. */

    private fun market(order: DeribitOrder) =
        NewOrder(
            order.label!!,
            "BTC_USDC-PERPETUAL",
            Side.BUY,
            OrderType.MARKET,
            order.amount,
            timeInForce = TimeInForce.GTC,
        )

    @Test
    fun `the incident's market order deribit turned into a far limit maps as the market order it was`() {
        val rested = order(result("order-state-market-remainder-rested.json"))
        val trades =
            result(
                "trades-market-remainder-rested.json",
            ).jsonArray.map { DeribitPrivateJson.trade(it.jsonObject) }

        val mapped = DeribitMapping.order(rested)!!

        assertThat(rested.orderType).isEqualTo("limit")
        assertThat(mapped.type).isEqualTo(OrderType.MARKET)
        assertThat(mapped.limitPrice).isNull()
        assertThat(mapped.filledQuantity).isEqualByComparingTo("50")
        assertThat(
            trades.mapNotNull(DeribitMapping::fill).map { it.price.toPlainString() to it.quantity.toPlainString() },
        ).containsExactly("133.712" to "20.0", "121.601" to "30.0")
    }

    @Test
    fun `a market order answered with a remainder working is cancelled at once and answered with what filled`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = adapter(dir)
        deribit.cancels[placed.orderId] = { cancelled }

        val answer = adapter.place(market(placed))

        assertThat(placed.state).isEqualTo("open")
        assertThat(placed.filledAmount).isPositive.isLessThan(placed.amount)
        assertThat(answer.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(answer.type).isEqualTo(OrderType.MARKET)
        assertThat(answer.filledQuantity).isEqualByComparingTo(placed.filledAmount)
        assertThat(answer.limitPrice).isNull()
        adapter.close()
    }

    @Test
    fun `a remainder that filled before its cancel reached it is reported filled`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = adapter(dir)
        val filled = placed.copy(state = "filled", filledAmount = placed.amount)
        deribit.cancels[placed.orderId] = { throw refusal }
        deribit.byLabel[placed.label!!] = listOf(filled)

        val answer = adapter.place(market(placed))

        assertThat(answer.status).isEqualTo(OrderStatus.FILLED)
        assertThat(answer.filledQuantity).isEqualByComparingTo(placed.amount)
        adapter.close()
    }

    @Test
    fun `a refused cancel stands while the remainder still works, so it is never reported as working`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = adapter(dir)
        deribit.cancels[placed.orderId] = { throw refusal }
        deribit.byLabel[placed.label!!] = listOf(placed)

        assertThatThrownBy { adapter.orderByLabel(placed.label!!) }.isInstanceOf(VenueRefusedException::class.java)
        adapter.close()
    }

    @Test
    fun `a pushed remainder is held back while its cancel goes out unawaited, and the cancelled push is reported`(
        @TempDir dir: Path,
    ) {
        val (_, listener) = adapter(dir)
        val pushes = pushed("ws-market-remainder.json")
        val open = pushes.first { it.state == "open" && it.originalOrderType == "market" }

        deribit.onOrder(open)
        assertThat(listener.orders).isEmpty()
        assertThat(deribit.cancelsSent).containsExactly(open.orderId)

        deribit.onOrder(pushes.last())
        val reported = listener.orders.single()
        assertThat(reported.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(reported.type).isEqualTo(OrderType.MARKET)
        assertThat(reported.filledQuantity).isEqualByComparingTo(open.filledAmount)
    }

    @Test
    fun `a remainder among the open orders is cancelled and no longer listed`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = adapter(dir)
        val read = result("order-state-by-label-market-remainder-open.json").jsonArray.map(::order).single()
        deribit.listed = listOf(read)
        deribit.cancels[read.orderId] = { cancelled }

        assertThat(adapter.openOrders()).isEmpty()
        adapter.close()
    }

    @Test
    fun `a fired stop-market deribit left working is cancelled and reported as the stop it was`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = adapter(dir)
        val states = result("order-state-by-label-stop-remainder-open.json").jsonArray.map(::order)
        val fired = states.single { it.state == "open" }
        deribit.byLabel[fired.label!!] = states
        deribit.cancels[fired.orderId] = { order(result("cancel-stop-remainder.json")) }

        val reported = adapter.orderByLabel(fired.label!!)!!

        assertThat(fired.triggered).isTrue
        assertThat(reported.type).isEqualTo(OrderType.STOP)
        assertThat(reported.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(reported.filledQuantity).isEqualByComparingTo(fired.filledAmount).isLessThan(fired.amount)
        adapter.close()
    }
}
