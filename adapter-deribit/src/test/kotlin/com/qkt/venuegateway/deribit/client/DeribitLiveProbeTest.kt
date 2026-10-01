package com.qkt.venuegateway.deribit.client

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** Against the real venue, only with `DERIBIT_LIVE=1`: the public ticker channel parses as the REST ticker does. */
@EnabledIfEnvironmentVariable(named = "DERIBIT_LIVE", matches = "1")
class DeribitLiveProbeTest {
    @Test
    fun `a live ticker subscription delivers parsed tickers`() {
        val tickers = LinkedBlockingQueue<DeribitTicker>()
        val raw = LinkedBlockingQueue<String>()
        val stream =
            DeribitTickerStream(onTicker = { tickers += it }, onConnection = { up, why ->
                raw +=
                    "conn $up $why"
            })
        stream.start()
        stream.subscribe(setOf("BTC_USDC-PERPETUAL"))
        val ticker = tickers.poll(20, TimeUnit.SECONDS)
        stream.close()

        assertThat(ticker).isNotNull()
        assertThat(ticker!!.name).isEqualTo("BTC_USDC-PERPETUAL")
        assertThat(ticker.bid).isNotNull()
        assertThat(ticker.mark).isNotNull()
        System.getenv("DERIBIT_RECORD")?.let { Files.writeString(Path.of(it), ticker.toString()) }
    }
}
