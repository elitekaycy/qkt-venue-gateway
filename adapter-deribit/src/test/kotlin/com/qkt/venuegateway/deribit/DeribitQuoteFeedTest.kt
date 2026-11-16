package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitTickerStream
import com.qkt.venuegateway.deribit.client.DeribitTickers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The quote feed over a ticker link that records what it is asked. */
class DeribitQuoteFeedTest {
    private val subscribed = CopyOnWriteArrayList<Set<String>>()
    private val started = AtomicInteger()
    private val closed = AtomicInteger()
    private val tickers =
        object : DeribitTickers {
            override fun start() {
                started.incrementAndGet()
            }

            override fun subscribe(names: Set<String>) {
                subscribed += names
            }

            override fun close() {
                closed.incrementAndGet()
            }
        }

    @Test
    fun `the codes clients want reach the ticker link, and upkeep keeps its timer after a failure`() {
        val upkeeps = CountDownLatch(2)
        val feed =
            DeribitQuoteFeed(
                tickers,
                upkeep = {
                    upkeeps.countDown()
                    error("disk full")
                },
            )

        feed.start(10)
        feed.want(setOf("BTC_USDC-PERPETUAL"))

        assertThat(upkeeps.await(5, TimeUnit.SECONDS)).describedAs("upkeep ran again after it failed").isTrue
        feed.close()
        assertThat(subscribed).containsExactly(setOf("BTC_USDC-PERPETUAL"))
        assertThat(started.get()).isEqualTo(1)
        assertThat(closed.get()).isEqualTo(1)
    }

    @Test
    fun `a ticker stream on its default url sends nothing and hears nothing before it starts`() {
        val heard = CopyOnWriteArrayList<Any>()
        val stream = DeribitTickerStream(onTicker = { heard += it }, onConnection = { up, _ -> heard += up })

        stream.subscribe(setOf("BTC_USDC-PERPETUAL"))
        stream.close()

        assertThat(heard).isEmpty()
    }
}
