package com.qkt.venuegateway.paper

import com.qkt.venuegateway.deribit.client.DeribitTickers
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Test

class PaperFeedTest {
    private val subscribed = CopyOnWriteArrayList<Set<String>>()

    @Volatile private var trading = listOf("BTC_USDC-PERPETUAL")

    @Test
    fun `the subscription is what clients want and what the account trades, renewed as the account trades more`() {
        val feed = PaperFeed(refreshMs = 20) { trading }
        feed.start(
            object : DeribitTickers {
                override fun start() {}

                override fun subscribe(names: Set<String>) {
                    subscribed += names
                }

                override fun close() {}
            },
        )
        feed.want(setOf("BTC_USDC-2OCT26-80000-P"))
        trading = trading + "ETH_USDC-PERPETUAL"

        val deadline = System.currentTimeMillis() + 5_000
        val all = setOf("BTC_USDC-2OCT26-80000-P", "BTC_USDC-PERPETUAL", "ETH_USDC-PERPETUAL")
        while (subscribed.none { it == all }) {
            check(System.currentTimeMillis() < deadline) { "never subscribed what the account trades: $subscribed" }
            Thread.sleep(10)
        }
        feed.close()
    }
}
