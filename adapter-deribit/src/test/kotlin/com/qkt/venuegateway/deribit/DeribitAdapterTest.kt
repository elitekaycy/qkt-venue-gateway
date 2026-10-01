package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.TradeMode
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.deribit.client.DeribitAccount
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitNewOrder
import com.qkt.venuegateway.deribit.client.DeribitOrder
import com.qkt.venuegateway.deribit.client.DeribitPosition
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import com.qkt.venuegateway.deribit.client.DeribitTickers
import com.qkt.venuegateway.deribit.client.DeribitTrade
import com.qkt.venuegateway.deribit.client.DeribitTrading
import com.qkt.venuegateway.testkit.RecordingListener
import java.math.BigDecimal
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The adapter's own decisions, over a scripted Deribit answering with the testnet recordings. */
class DeribitAdapterTest {
    private fun recorded(name: String) =
        Json
            .parseToJsonElement(javaClass.getResource("/fixtures/private/$name")!!.readText())
            .jsonObject["response"]!!
            .jsonObject["result"]!!

    private val open = DeribitPrivateJson.order(recorded("buy-limit-open.json").jsonObject["order"]!!.jsonObject)
    private val stopLabelled =
        recorded("order-state-by-label-stop-triggered.json").jsonArray.map {
            DeribitPrivateJson.order(it.jsonObject)
        }

    private inner class ScriptedDeribit : DeribitTrading {
        val byLabel = mutableMapOf<String, List<DeribitOrder>>()
        val edits = mutableListOf<String>()
        var cancelled = 0
        lateinit var onOrder: (DeribitOrder) -> Unit
        lateinit var onTrade: (DeribitTrade) -> Unit
        lateinit var onConnection: (Boolean, String) -> Unit

        override fun start() = onConnection(true, "open")

        override fun account(currency: String) =
            DeribitAccount(
                currency,
                BigDecimal("100"),
                BigDecimal("101"),
                BigDecimal("5"),
                BigDecimal("3"),
                BigDecimal("96"),
            )

        override fun positions(currency: String) = emptyList<DeribitPosition>()

        override fun openOrders(currency: String) = listOf(open, open.copy(label = null))

        override fun ordersByLabel(
            currency: String,
            label: String,
        ) = byLabel[label].orEmpty()

        override fun place(order: DeribitNewOrder) = open

        override fun cancelByLabel(
            currency: String,
            label: String,
        ) = cancelled

        override fun editByLabel(
            label: String,
            instrument: String,
            amount: BigDecimal,
            price: BigDecimal?,
            triggerPrice: BigDecimal?,
        ): DeribitOrder {
            edits += "$label $instrument ${amount.toPlainString()} ${price?.toPlainString()}"
            return open.copy(amount = amount, price = price ?: open.price)
        }

        override fun trades(
            currency: String,
            fromMs: Long,
            toMs: Long,
        ) = emptyList<DeribitTrade>()

        override fun close() {}
    }

    private val deribit = ScriptedDeribit()
    private var tickerConnection: (Boolean, String) -> Unit = { _, _ -> }

    private fun adapter(dir: Path): Pair<DeribitAdapter, RecordingListener> {
        val adapter =
            DeribitAdapter(
                AdapterContext(mapOf("environment" to "testnet"), { 0L }, dir, Credentials("client-7", "s")),
                unusedMarket(),
                { onOrder, onTrade, onConnection ->
                    deribit.also {
                        it.onOrder = onOrder
                        it.onTrade = onTrade
                        it.onConnection = onConnection
                    }
                },
                { _, onConnection ->
                    tickerConnection = onConnection
                    object : DeribitTickers {
                        override fun start() {}

                        override fun subscribe(names: Set<String>) {}

                        override fun close() {}
                    }
                },
            )
        val listener = RecordingListener()
        adapter.connect(listener)
        return adapter to listener
    }

    private fun unusedMarket(): DeribitMarketData =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(DeribitMarketData::class.java),
        ) { _, method, _ ->
            error("market ${method.name} is not used here")
        } as DeribitMarketData

    @Test
    fun `the account is named by the api key's client id, demo on testnet, and its margin is mapped`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = adapter(dir)

        assertThat(adapter.identity().login).isEqualTo("client-7")
        assertThat(adapter.identity().mode).isEqualTo(TradeMode.DEMO)
        val account = adapter.account()
        assertThat(account.marginUsed).isEqualByComparingTo("5")
        assertThat(account.marginAvailable).isEqualByComparingTo("96")
        assertThat(account.maintenanceMargin).isEqualByComparingTo("3")
        adapter.close()
    }

    @Test
    fun `a cancel answers the order as it now stands, and an order Deribit never saw is null`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = adapter(dir)
        deribit.byLabel["kit-probe-1790866831"] =
            listOf(open.copy(state = "filled", filledAmount = open.amount, averagePrice = BigDecimal("1")))

        assertThat(adapter.cancel("kit-probe-1790866831")?.status).isEqualTo(OrderStatus.FILLED)
        assertThat(adapter.cancel("never-sent")).isNull()
        adapter.close()
    }

    @Test
    fun `of two orders under one label the newest is the order, as a fired stop is newer than its stop`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = adapter(dir)
        deribit.byLabel["kit-trig3-1790867232"] = stopLabelled

        assertThat(adapter.orderByLabel("kit-trig3-1790867232")?.status).isEqualTo(OrderStatus.FILLED)
        adapter.close()
    }

    @Test
    fun `a modify edits by label keeping the quantity it does not change, and an unknown order is null`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = adapter(dir)
        deribit.byLabel["kit-probe-1790866831"] = listOf(open)

        val edited = adapter.modify("kit-probe-1790866831", OrderChange(limitPrice = BigDecimal("50000")))

        assertThat(deribit.edits).containsExactly("kit-probe-1790866831 BTC_USDC-PERPETUAL 0.0001 50000")
        assertThat(edited?.limitPrice).isEqualByComparingTo("50000")
        assertThat(adapter.modify("never-sent", OrderChange(limitPrice = BigDecimal.ONE))).isNull()
        adapter.close()
    }

    @Test
    fun `only the account link is the venue connection, and unlabelled orders are never reported`(
        @TempDir dir: Path,
    ) {
        val (adapter, listener) = adapter(dir)

        tickerConnection(false, "ticker socket dropped")
        deribit.onConnection(false, "account socket dropped")
        deribit.onOrder(open.copy(label = null))
        deribit.onOrder(open)

        assertThat(listener.connections).containsExactly(true, false)
        assertThat(listener.orders.map { it.clientOrderId }).containsExactly("kit-probe-1790866831")
        assertThat(adapter.openOrders().map { it.clientOrderId }).containsExactly("kit-probe-1790866831")
        adapter.close()
    }

    @Test
    fun `settlements are refused as unavailable until a delivery has been recorded from the venue`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = adapter(dir)

        assertThatThrownBy { adapter.settlements(0, 1) }.isInstanceOf(VenueUnavailableException::class.java)
        adapter.close()
    }
}
