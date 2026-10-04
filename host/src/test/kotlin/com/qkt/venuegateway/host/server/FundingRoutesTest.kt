package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueFundingRate
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import com.qkt.vgp.WireFundingRates
import com.qkt.vgp.WireFundings
import com.qkt.vgp.WireHealth
import java.math.BigDecimal
import java.nio.file.Path
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FundingRoutesTest {
    @TempDir lateinit var dir: Path
    private val venue = FakeAdapter()
    private val http = OkHttpClient()
    private val hour = 3_600_000L
    private val now = 5_000 * hour
    private lateinit var gateway: Gateway
    private lateinit var hub: QuoteHub
    private lateinit var server: GatewayServer
    private var base = ""

    private fun start() {
        gateway =
            Gateway(
                venue,
                Journal.open(dir.resolve("j.db")),
                InstrumentShelf.open(dir.resolve("i.db")) { now },
                mapOf(Role.TRADER to "t"),
            ) { now }
        gateway.start()
        venue.listener!!.connection(true, "test")
        hub = QuoteHub(gateway)
        server = GatewayServer(gateway, hub, "127.0.0.1", 0)
        base = "http://127.0.0.1:${server.start()}"
    }

    @AfterEach
    fun stop() {
        server.close()
        hub.close()
        gateway.close()
    }

    private fun get(path: String): Pair<Int, String> =
        http
            .newCall(
                Request
                    .Builder()
                    .url(base + path)
                    .header("Authorization", "Bearer t")
                    .build(),
            ).execute()
            .use { it.code to it.body!!.string() }

    private fun funding(
        id: String,
        time: Long,
    ) = VenueFunding(id, "SOL_USDC-PERPETUAL", BigDecimal("0.0123"), "USDC", BigDecimal("150"), time)

    @Test
    fun `health names the capabilities the adapter declares, in a stable order`() {
        venue.capabilities = setOf(Capability.FUNDING_RATES, Capability.BARS, Capability.FUNDING)
        start()

        val health = wireJson.decodeFromString(WireHealth.serializer(), get("/v1/health").second)

        assertThat(health.capabilities).containsExactly("bars", "funding", "funding_rates")
    }

    @Test
    fun `a funding record pushed twice is journaled once and served in its window`() {
        start()
        venue.listener!!.funding(funding("f-1", now - hour))
        venue.listener!!.funding(funding("f-1", now - hour))
        venue.listener!!.funding(funding("f-2", now - 3 * hour))

        val (code, body) = get("/v1/funding?from=${now - 2 * hour}&to=$now")

        assertThat(code).isEqualTo(200)
        val served = wireJson.decodeFromString(WireFundings.serializer(), body).funding
        assertThat(served.map { it.fundingId }).containsExactly("f-1")
        assertThat(served.single().amount).isEqualTo("0.0123")
        assertThat(served.single().position).isEqualTo("150")
        assertThat(gateway.journal.eventsAfter(0).count { it.type == "funding" }).isEqualTo(2)
    }

    @Test
    fun `funding rates are served a thousand hours at a time, oldest first, with the next page's start`() {
        venue.rates += VenueFundingRate(2 * hour, BigDecimal("0.00001"), BigDecimal("121.5"))
        venue.rates += VenueFundingRate(hour, BigDecimal("-0.00002"), null)
        start()

        val (code, body) = get("/v1/funding-rates?symbol=SOL_USDC-PERPETUAL&from=$hour&to=$now")

        assertThat(code).isEqualTo(200)
        val page = wireJson.decodeFromString(WireFundingRates.serializer(), body)
        assertThat(page.rates.map { it.time }).containsExactly(hour, 2 * hour)
        assertThat(page.rates.first().price).isNull()
        assertThat(page.rates.last().rate).isEqualTo("0.00001")
        assertThat(page.next).isEqualTo(1_001 * hour)
        assertThat(venue.rateCalls.single()).isEqualTo(hour to 1_001 * hour - 1)
    }

    @Test
    fun `the last page of funding rates ends at now and has no next`() {
        start()

        val page =
            wireJson.decodeFromString(
                WireFundingRates.serializer(),
                get("/v1/funding-rates?symbol=SOL_USDC-PERPETUAL&from=${now - hour}&to=${now + hour}").second,
            )

        assertThat(page.next).isNull()
        assertThat(venue.rateCalls.single()).isEqualTo(now - hour to now)
    }

    @Test
    fun `funding and funding rates are refused as unsupported when the adapter does not declare them`() {
        venue.capabilities = setOf(Capability.BARS, Capability.QUOTES)
        start()

        val funding = get("/v1/funding?from=0&to=$now")
        val rates = get("/v1/funding-rates?symbol=SOL_USDC-PERPETUAL&from=0&to=$now")

        assertThat(funding.first).isEqualTo(501)
        assertThat(funding.second).contains("\"code\":\"unsupported\"").contains("funding")
        assertThat(rates.first).isEqualTo(501)
        assertThat(venue.rateCalls).isEmpty()
    }
}
