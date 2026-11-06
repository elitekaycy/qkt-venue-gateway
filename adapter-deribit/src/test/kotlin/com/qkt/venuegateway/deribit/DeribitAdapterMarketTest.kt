package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import com.qkt.venuegateway.deribit.client.DeribitTicker
import com.qkt.venuegateway.deribit.client.DeribitTickers
import com.qkt.venuegateway.deribit.client.DeribitTrading
import com.qkt.venuegateway.testkit.RecordingListener
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The adapter's market and account reads, over a market and an account answering with the recordings. */
class DeribitAdapterMarketTest {
    private fun json(path: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/$path")!!.readText()).jsonObject

    private fun result(name: String) = json("private/$name")["response"]!!.jsonObject["result"]!!

    private val open = DeribitPrivateJson.order(result("buy-limit-open.json").jsonObject["order"]!!.jsonObject)
    private val recordedTrades = DeribitPrivateJson.tradePage(result("user-trades-by-time.json").jsonObject).items
    private val recordedPositions =
        result("positions-future-linear.json").jsonArray.map { DeribitPrivateJson.position(it.jsonObject) }
    private val deribit = ScriptedDeribit(open)
    private val account =
        object : DeribitTrading by deribit {
            override fun positions(currency: String) = recordedPositions

            override fun trades(
                currency: String,
                fromMs: Long,
                toMs: Long,
            ) = recordedTrades
        }
    private val klinesAsked = CopyOnWriteArrayList<Long>()
    private val subscribed = CopyOnWriteArrayList<Set<String>>()
    private var onTicker: (DeribitTicker) -> Unit = {}

    private val market =
        object : DeribitMarketData {
            override fun instruments(
                currency: String,
                kind: String,
            ) = json("instruments-$kind.json")["result"]!!.jsonArray.map { DeribitJson.instrument(it.jsonObject) }

            override fun instrument(name: String): DeribitInstrument = error("unused")

            override fun ticker(name: String): DeribitTicker = error("unused")

            override fun klines(
                name: String,
                minutes: Long,
                fromMs: Long,
                toMs: Long,
            ): List<DeribitKline> {
                klinesAsked += minutes
                return DeribitJson.klines(json("chart-1m.json")["result"]!!.jsonObject)
            }

            override fun fundingRates(
                name: String,
                fromMs: Long,
                toMs: Long,
            ) = json("funding-rate-history.json")["result"]!!.jsonArray.map { DeribitJson.fundingRate(it.jsonObject) }

            override fun deliveryPrices(
                index: String,
                count: Int,
            ) = emptyList<Pair<LocalDate, BigDecimal>>()
        }

    private fun adapter(dir: Path): Pair<DeribitAdapter, RecordingListener> {
        val adapter =
            DeribitAdapter(
                AdapterContext(mapOf("environment" to "testnet"), { 0L }, dir, Credentials("client-7", "s")),
                market,
                { onOrder, onTrade, onConnection ->
                    deribit.onOrder = onOrder
                    deribit.onTrade = onTrade
                    deribit.onConnection = onConnection
                    account
                },
                { heard, _ ->
                    onTicker = heard
                    object : DeribitTickers {
                        override fun start() {}

                        override fun subscribe(names: Set<String>) {
                            subscribed += names
                        }

                        override fun close() {}
                    }
                },
            )
        val listener = RecordingListener()
        adapter.connect(listener)
        return adapter to listener
    }

    @Test
    fun `the listing, klines and funding rates are read from the market and mapped`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = adapter(dir)
        val from = 1_790_863_980_000L

        val codes = adapter.instruments().map { it.code }
        val bars = adapter.klines("BTC_USDC-PERPETUAL", 60_000, from, from + 180_000)
        val rates = adapter.fundingRates("SOL_USDC-PERPETUAL", 1_791_122_400_000L, 1_791_129_600_000L)

        assertThat(codes).contains("BTC_USDC-PERPETUAL", "1000BONK_USDC-PERPETUAL")
        assertThat(klinesAsked).containsExactly(1L)
        assertThat(bars.map { it.startMs }).containsExactly(from, from + 60_000, from + 120_000)
        assertThat(bars.first().open.toPlainString()).isEqualTo("83805.7")
        assertThat(rates).hasSize(3)
        assertThat(rates.first().timeMs).isEqualTo(1_791_122_400_000L)
        assertThat(rates.first().price).isEqualByComparingTo("121.6965")
        adapter.close()
    }

    @Test
    fun `positions are netted in coins and fills carry the label, both pushed and read back`(
        @TempDir dir: Path,
    ) {
        val (adapter, listener) = adapter(dir)

        val held = adapter.positions()
        val fills = adapter.fills(1_790_866_864_590L, 1_790_866_877_110L)
        deribit.onTrade(recordedTrades.first())

        assertThat(held.accounting).isEqualTo(Accounting.NETTING)
        assertThat(held.rows.single().quantity).isEqualByComparingTo("0.0001")
        assertThat(fills.map { it.fillId }).containsExactly("USDC-55455987", "USDC-55455988")
        assertThat(fills.first().clientOrderId).isEqualTo("kit-fill-1790866869-o")
        assertThat(fills.first().side).isEqualTo(Side.BUY)
        assertThat(listener.fills.map { it.fillId }).containsExactly("USDC-55455987")
        adapter.close()
    }

    @Test
    fun `quotes are subscribed on the ticker link and each ticker reaches the listener as a quote`(
        @TempDir dir: Path,
    ) {
        val (adapter, listener) = adapter(dir)

        adapter.subscribeQuotes(setOf("BTC_USDC-PERPETUAL"))
        onTicker(DeribitJson.ticker(json("ticker-perp.json")["result"]!!.jsonObject))

        assertThat(subscribed).containsExactly(setOf("BTC_USDC-PERPETUAL"))
        assertThat(listener.quotes.single().symbol).isEqualTo("BTC_USDC-PERPETUAL")
        assertThat(listener.quotes.single().bid).isEqualByComparingTo("84012.2")
        assertThat(listener.quotes.single().ask).isEqualByComparingTo("84012.3")
        adapter.close()
    }

    @Test
    fun `open interest and depth refuse a code that is not an instrument name before reading anything`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = adapter(dir)

        assertThatThrownBy { adapter.openInterest("BTC USDC", 0, 1) }.isInstanceOf(VenueRefusedException::class.java)
        assertThatThrownBy { adapter.depth("../BTC", 0, 1) }.isInstanceOf(VenueRefusedException::class.java)
        adapter.close()
    }
}
