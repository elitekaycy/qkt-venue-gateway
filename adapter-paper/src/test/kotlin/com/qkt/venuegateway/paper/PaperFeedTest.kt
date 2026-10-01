package com.qkt.venuegateway.paper

import com.qkt.venuegateway.deribit.DeribitListing
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitTicker
import com.qkt.venuegateway.deribit.client.DeribitTickers
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Test

class PaperFeedTest {
    @Volatile private var options = listOf("BTC_USDC-2OCT26-80000-P")
    private val subscribed = CopyOnWriteArrayList<Set<String>>()

    private val market =
        object : DeribitMarketData {
            override fun instruments(
                currency: String,
                kind: String,
            ) = if (kind == "option") options.map(::option) else emptyList()

            override fun instrument(name: String) = option(name)

            override fun ticker(name: String): DeribitTicker = error("unused")

            override fun klines(
                name: String,
                minutes: Long,
                fromMs: Long,
                toMs: Long,
            ) = emptyList<DeribitKline>()

            override fun deliveryPrices(
                index: String,
                count: Int,
            ) = emptyList<Pair<LocalDate, BigDecimal>>()
        }

    private fun option(name: String) =
        DeribitInstrument(
            name,
            "option",
            false,
            1L,
            BigDecimal("80000"),
            "put",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            "USDC",
            "btc_usdc",
        )

    @Test
    fun `a root subscription takes up options listed after it, at the next refresh`() {
        var now = 0L
        val feed = PaperFeed(DeribitListing(market, "USDC", { now }, refreshMs = 10), refreshMs = 20) { emptyList() }
        feed.start(
            object : DeribitTickers {
                override fun start() {}

                override fun subscribe(names: Set<String>) {
                    subscribed += names
                }

                override fun close() {}
            },
        )
        feed.want(emptySet(), setOf("BTC_USDC"))
        options = options + "BTC_USDC-3OCT26-80000-P"
        now = 100

        val deadline = System.currentTimeMillis() + 5_000
        while (subscribed.none { "BTC_USDC-3OCT26-80000-P" in it }) {
            check(System.currentTimeMillis() < deadline) { "never subscribed the new expiry: $subscribed" }
            Thread.sleep(10)
        }
        feed.close()
    }
}
