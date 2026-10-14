package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.deribit.client.DeribitAccount
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitNewOrder
import com.qkt.venuegateway.deribit.client.DeribitOrder
import com.qkt.venuegateway.deribit.client.DeribitPosition
import com.qkt.venuegateway.deribit.client.DeribitSettlement
import com.qkt.venuegateway.deribit.client.DeribitTickers
import com.qkt.venuegateway.deribit.client.DeribitTrade
import com.qkt.venuegateway.deribit.client.DeribitTrading
import com.qkt.venuegateway.deribit.client.DeribitTransaction
import com.qkt.venuegateway.testkit.RecordingListener
import java.math.BigDecimal
import java.nio.file.Path

/** A scripted Deribit account answering every call with [open] and what a test sets. */
internal class ScriptedDeribit(
    private val open: DeribitOrder,
) : DeribitTrading {
    val byLabel = mutableMapOf<String, List<DeribitOrder>>()
    val edits = mutableListOf<String>()
    val transactions = mutableListOf<DeribitTransaction>()
    val settled = mutableListOf<DeribitSettlement>()
    var logReads = 0
    var cancelled = 0
    var placed: DeribitOrder? = null
    val cancels = mutableMapOf<String, () -> DeribitOrder>()
    val cancelsSent = mutableListOf<String>()
    var listed: List<DeribitOrder>? = null
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

    override fun openOrders(currency: String) = listed ?: listOf(open, open.copy(label = null))

    override fun ordersByLabel(
        currency: String,
        label: String,
    ) = byLabel[label].orEmpty()

    override fun place(order: DeribitNewOrder) = placed ?: open

    override fun cancel(orderId: String) = cancels.getValue(orderId)()

    override fun cancelSoon(orderId: String) {
        cancelsSent += orderId
    }

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

    override fun transactions(
        currency: String,
        fromMs: Long,
        toMs: Long,
    ): List<DeribitTransaction> {
        logReads++
        return transactions.filter { it.timestampMs in fromMs..toMs }
    }

    override fun settlements(
        currency: String,
        fromMs: Long,
        toMs: Long,
    ) = settled.filter { it.timestampMs in fromMs..toMs }

    override fun trades(
        currency: String,
        fromMs: Long,
        toMs: Long,
    ) = emptyList<DeribitTrade>()

    override fun close() {}

    /** A testnet adapter on this account and [market], with more [settings], connected to a [RecordingListener]. */
    fun adapter(
        dir: Path,
        market: DeribitMarketData,
        history: DeribitMarketData = market,
        settings: Map<String, String> = emptyMap(),
        onTickerConnection: ((Boolean, String) -> Unit) -> Unit = {},
    ): Pair<DeribitAdapter, RecordingListener> {
        val adapter =
            DeribitAdapter(
                AdapterContext(mapOf("environment" to "testnet") + settings, { 0L }, dir, Credentials("client-7", "s")),
                market,
                { onOrder, onTrade, onConnection ->
                    also {
                        it.onOrder = onOrder
                        it.onTrade = onTrade
                        it.onConnection = onConnection
                    }
                },
                { _, onConnection ->
                    onTickerConnection(onConnection)
                    object : DeribitTickers {
                        override fun start() {}

                        override fun subscribe(names: Set<String>) {}

                        override fun close() {}
                    }
                },
                history = history,
            )
        val listener = RecordingListener()
        adapter.connect(listener)
        return adapter to listener
    }
}

/** A market that fails any call: for tests where the adapter must not touch it. */
internal fun unusedMarket(): DeribitMarketData =
    java.lang.reflect.Proxy.newProxyInstance(
        ScriptedDeribit::class.java.classLoader,
        arrayOf(DeribitMarketData::class.java),
    ) { _, method, _ ->
        error("market ${method.name} is not used here")
    } as DeribitMarketData
