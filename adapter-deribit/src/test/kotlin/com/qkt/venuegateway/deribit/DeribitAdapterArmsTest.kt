package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitOrder
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import com.qkt.venuegateway.deribit.client.DeribitTicker
import com.qkt.venuegateway.deribit.client.DeribitTickers
import com.qkt.venuegateway.deribit.client.DeribitTrade
import com.qkt.venuegateway.deribit.client.recordedObject
import com.qkt.venuegateway.deribit.client.recordedResult
import com.qkt.venuegateway.testkit.RecordingListener
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DeribitAdapterArmsTest {
    @TempDir lateinit var dir: Path
    private val open: DeribitOrder =
        DeribitPrivateJson.order(recordedResult("private/buy-limit-open.json").jsonObject["order"]!!.jsonObject)
    private val trade: DeribitTrade =
        DeribitPrivateJson.trade(
            (recordedObject("private/user-trades-by-time.json")["trades"] as JsonArray).first().jsonObject,
        )
    private val ticker: DeribitTicker = DeribitJson.ticker(recordedObject("ticker-perp.json"))
    private val deribit = ScriptedDeribit(open)
    private lateinit var onTicker: (DeribitTicker) -> Unit
    private lateinit var onFeed: (Boolean, String) -> Unit

    /** An adapter wired to the scripted account, not yet connected to a listener. */
    private fun adapter() =
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
            { ticker, feed ->
                onTicker = ticker
                onFeed = feed
                object : DeribitTickers {
                    override fun start() {}

                    override fun subscribe(names: Set<String>) {}

                    override fun close() {}
                }
            },
        )

    @Test
    fun `what the venue pushes before a listener is connected is dropped, not kept for it`() {
        val adapter = adapter()
        deribit.onOrder(open)
        deribit.onTrade(trade.copy(label = "c-1"))
        deribit.onConnection(true, "open")
        onTicker(ticker)
        onFeed(true, "open")

        val listener = RecordingListener()
        adapter.connect(listener)

        assertThat(listener.orders).isEmpty()
        assertThat(listener.fills).isEmpty()
        assertThat(listener.quotes).isEmpty()
        assertThat(listener.quoteFeed).isEmpty()
        adapter.close()
    }

    @Test
    fun `an order or trade of no label of the gateway's is not pushed, and a pushed quote is`() {
        val adapter = adapter()
        val listener = RecordingListener()
        adapter.connect(listener)

        deribit.onOrder(open.copy(label = null))
        deribit.onOrder(open)
        deribit.onTrade(trade.copy(label = null))
        onTicker(ticker)

        assertThat(listener.orders.map { it.clientOrderId }).containsExactly(open.label)
        assertThat(listener.fills).isEmpty()
        assertThat(listener.quotes.single().symbol).isEqualTo(ticker.name)
        adapter.close()
    }

    @Test
    fun `a market remainder pushed by the venue is cancelled and never reported as an order`() {
        val adapter = adapter()
        val listener = RecordingListener()
        adapter.connect(listener)
        val remainder = open.copy(originalOrderType = "market", orderType = "limit")

        deribit.onOrder(remainder)

        assertThat(listener.orders).isEmpty()
        assertThat(deribit.cancelsSent).containsExactly(remainder.orderId)
        adapter.close()
    }
}
