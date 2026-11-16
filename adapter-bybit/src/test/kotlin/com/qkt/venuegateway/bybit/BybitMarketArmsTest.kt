package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.bybit.client.BybitPublicClient
import com.qkt.venuegateway.bybit.client.BybitRest
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BybitMarketArmsTest {
    @TempDir lateinit var dir: Path
    private val server = Fixtures.Server { path, query -> BybitVenue.linearRoute(path, query) }
    private val now = BybitVenue.NOW

    @AfterEach
    fun stop() = server.close()

    private fun market(): BybitMarket {
        val context =
            AdapterContext(BybitVenue.LINEAR, { now }, dir, Credentials("k", "s"), recordingRetentionDays = 1)
        val settings = BybitSettings.of(context)
        val public = BybitPublicClient(BybitRest(server.url))
        val listing = BybitListing({ public.instruments("linear") }, "USDT", { now }, 600_000)
        return BybitMarket(public, settings, listing, context) { }
    }

    @Test
    fun `a recording that cannot be pruned is logged and the read it came with is still answered`() {
        val stuck = dir.resolve("liquidations/BTCUSDT/2020-01-01.csv")
        Files.createDirectories(stuck)
        Files.writeString(stuck.resolve("held"), "x")

        val served = market().liquidations("BTCUSDT", now - 1_000, now)

        assertThat(served).isEmpty()
        assertThat(Files.exists(stuck)).describedAs("the stuck file stays").isTrue
    }
}
