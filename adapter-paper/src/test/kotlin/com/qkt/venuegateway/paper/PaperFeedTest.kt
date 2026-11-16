package com.qkt.venuegateway.paper

import com.qkt.venuegateway.deribit.client.DeribitTickers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
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

    @Test
    fun `before a feed is started wanting codes subscribes nothing and closing is safe`() {
        val feed = PaperFeed { trading }

        feed.want(setOf("BTC_USDC-PERPETUAL"))
        feed.close()

        assertThat(feed["BTC_USDC-PERPETUAL"]).isNull()
        assertThat(subscribed).isEmpty()
    }

    @Test
    fun `a renewal that fails is logged and the next one subscribes again`() {
        val reads = AtomicInteger()
        val feed =
            PaperFeed(refreshMs = 20) {
                check(reads.incrementAndGet() != 2) { "book busy" }
                trading
            }
        feed.start(
            object : DeribitTickers {
                override fun start() {}

                override fun subscribe(names: Set<String>) {
                    subscribed += names
                }

                override fun close() {}
            },
        )

        val deadline = System.currentTimeMillis() + 5_000
        while (subscribed.size < 2) {
            check(System.currentTimeMillis() < deadline) { "never renewed after a failure: $subscribed" }
            Thread.sleep(10)
        }
        feed.close()

        assertThat(reads.get()).isGreaterThanOrEqualTo(3)
        assertThat(subscribed.take(2)).containsOnly(setOf("BTC_USDC-PERPETUAL"))
    }
}
