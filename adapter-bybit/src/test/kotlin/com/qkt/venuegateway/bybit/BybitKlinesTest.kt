package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.bybit.client.BYBIT_KLINE_INTERVALS
import com.qkt.venuegateway.bybit.client.BybitPublicClient
import com.qkt.venuegateway.bybit.client.BybitRest
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BybitKlinesTest {
    @TempDir lateinit var dir: Path
    private val server =
        Fixtures.Server { path, query ->
            when (path to query["interval"]) {
                "/v5/market/instruments-info" to null -> "instruments-linear-BTCUSDT.json"
                "/v5/market/kline" to "1" -> "kline-linear-1m-15.json"
                "/v5/market/kline" to "5" -> "kline-linear-5m.json"
                else -> null
            }
        }
    private val public = BybitPublicClient(BybitRest(server.url))
    private val settings = mapOf("environment" to "testnet", "category" to "linear")
    private val from = 1_791_240_180_000L
    private val to = 1_791_241_080_000L

    private fun market(): BybitMarket {
        val listing = BybitListing({ public.instruments("linear") }, "USDT", { to }, 600_000)
        return BybitMarket(public, bybitSettings(settings), listing, AdapterContext(settings, { to }, dir)) {}
    }

    @AfterEach
    fun stop() = server.close()

    private fun klineRequests() = server.requests.filter { it.requestUrl!!.encodedPath == "/v5/market/kline" }

    @Test
    fun `bybit's kline lengths are served in milliseconds, from a minute to a day`() {
        assertThat(BYBIT_KLINE_INTERVALS.keys).contains(60_000L, 720 * 60_000L, 86_400_000L).hasSize(11)
        assertThat(BYBIT_KLINE_INTERVALS.getValue(86_400_000L)).isEqualTo("D")
    }

    @Test
    fun `a kline length is asked by bybit's interval name up to the last millisecond of the range`() {
        val klines = market().klines("BTCUSDT", 300_000, from, to)

        val url = klineRequests().single().requestUrl!!
        assertThat(url.queryParameter("interval")).isEqualTo("5")
        assertThat(url.queryParameter("start")).isEqualTo("$from")
        assertThat(url.queryParameter("end")).isEqualTo("${to - 1}")
        assertThat(
            klines.map { it.startMs },
        ).containsExactly(1_791_240_300_000L, 1_791_240_600_000L, 1_791_240_900_000L)
    }

    @Test
    fun `klines read back oldest first with bybit's volume, exactly as recorded`() {
        val klines = market().klines("BTCUSDT", 60_000, from, to)

        assertThat(klines).hasSize(15)
        assertThat(klines.zipWithNext().all { (a, b) -> b.startMs - a.startMs == 60_000L }).isTrue
        assertThat(klines.last().volume.toPlainString()).isEqualTo("0.048")
    }

    @Test
    fun `an unlisted code is refused even when no kline is asked for`() {
        assertThatThrownBy { market().klines("NOPEUSDT", 60_000, from, from) }
            .isInstanceOf(VenueRefusedException::class.java)
        assertThat(klineRequests()).isEmpty()
    }
}
