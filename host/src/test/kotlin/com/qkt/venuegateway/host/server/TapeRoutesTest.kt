package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenuePrint
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import com.qkt.vgp.WireHealth
import com.qkt.vgp.WireLiquidations
import com.qkt.vgp.WireTrades
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TapeRoutesTest {
    @TempDir lateinit var dir: Path

    /** A venue whose tape is [tape], answered as an adapter must: in range, oldest first, at most the limit. */
    private class TapeVenue : FakeAdapter() {
        val tape = CopyOnWriteArrayList<VenuePrint>()
        val liquidated = CopyOnWriteArrayList<VenuePrint>()
        val tradeCalls = CopyOnWriteArrayList<Triple<Long, Long, Int>>()
        val liquidationCalls = CopyOnWriteArrayList<Pair<Long, Long>>()

        override fun trades(
            code: String,
            fromMs: Long,
            toMs: Long,
            limit: Int,
        ) = tape
            .filter { it.timeMs in fromMs until toMs }
            .take(
                limit,
            ).also { tradeCalls += Triple(fromMs, toMs, limit) }

        override fun liquidations(
            code: String,
            fromMs: Long,
            toMs: Long,
        ) = liquidated.toList().also { liquidationCalls += fromMs to toMs }
    }

    private val venue = TapeVenue()
    private val http = OkHttpClient()
    private val hour = 3_600_000L
    private val now = 1_000 * hour + 30_000
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

    private fun trades(path: String) = wireJson.decodeFromString(WireTrades.serializer(), get(path).second)

    private fun liquidations(path: String) = wireJson.decodeFromString(WireLiquidations.serializer(), get(path).second)

    private fun print(
        id: String,
        time: Long,
        side: Side = Side.BUY,
        size: String = "0.005",
    ) = VenuePrint(id, time, BigDecimal("86457.10"), BigDecimal(size), side)

    @Test
    fun `health names trades and liquidations when the adapter declares them`() {
        venue.capabilities = setOf(Capability.TRADES, Capability.LIQUIDATIONS)
        start()

        val health = wireJson.decodeFromString(WireHealth.serializer(), get("/v1/health").second)

        assertThat(health.capabilities).containsExactly("trades", "liquidations")
    }

    @Test
    fun `trades are served oldest first with their aggressor side, exact sizes and the venue's ids`() {
        venue.tape += print("USDC-1", 100, Side.BUY)
        venue.tape += print("USDC-2", 150, Side.SELL, size = "0.0011")
        start()

        val page = trades("/v1/trades?symbol=BTC_USDC-PERPETUAL&from=100&to=200")

        assertThat(page.trades.map { it.id }).containsExactly("USDC-1", "USDC-2")
        assertThat(page.trades.map { it.side }).containsExactly("buy", "sell")
        assertThat(page.trades.last().size).isEqualTo("0.0011")
        assertThat(page.trades.last().price).isEqualTo("86457.10")
        assertThat(page.next).isNull()
    }

    @Test
    fun `a full page ends before its last millisecond, whose prints all open the next page`() {
        (0 until 999).forEach { venue.tape += print("a$it", 1_000L + it) }
        venue.tape += print("b0", 5_000)
        venue.tape += print("b1", 5_000)
        start()

        val first = trades("/v1/trades?symbol=BTC_USDC-PERPETUAL&from=1000&to=9000")
        val second = trades("/v1/trades?symbol=BTC_USDC-PERPETUAL&from=${first.next}&to=9000")

        assertThat(first.trades).hasSize(999)
        assertThat(first.next).isEqualTo(5_000L)
        assertThat(second.trades.map { it.id }).containsExactly("b0", "b1")
        assertThat(second.next).isNull()
        assertThat(venue.tradeCalls.first()).isEqualTo(Triple(1_000L, 9_000L, 1_000))
    }

    @Test
    fun `trades are served up to now, never a print stamped later`() {
        venue.tape += print("past", now - 1)
        venue.tape += print("ahead", now + 5)
        start()

        val page = trades("/v1/trades?symbol=BTC_USDC-PERPETUAL&from=${now - 10}&to=${now + 60_000}")

        assertThat(page.trades.map { it.id }).containsExactly("past")
        assertThat(venue.tradeCalls.single().second).isEqualTo(now)
    }

    @Test
    fun `liquidations are served an hour a page, in range, with the liquidated side`() {
        venue.liquidated += print("USDC-9", 2 * hour + 5, Side.SELL)
        venue.liquidated += print("USDC-8", hour + 10, Side.BUY)
        venue.liquidated += print("outside", 3 * hour)
        start()

        val page = liquidations("/v1/liquidations?symbol=BTC_USDC-PERPETUAL&from=$hour&to=$now")

        assertThat(page.liquidations.map { it.id }).containsExactly("USDC-8")
        assertThat(page.liquidations.single().side).isEqualTo("buy")
        assertThat(page.next).isEqualTo(2 * hour)
        assertThat(venue.liquidationCalls.single()).isEqualTo(hour to 2 * hour)
    }

    @Test
    fun `trades and liquidations are refused as unsupported when the adapter does not declare them`() {
        venue.capabilities = setOf(Capability.BARS, Capability.QUOTES)
        start()

        val (tradesCode, tradesBody) = get("/v1/trades?symbol=BTC_USDC-PERPETUAL&from=0&to=$now")
        val (liqCode, liqBody) = get("/v1/liquidations?symbol=BTC_USDC-PERPETUAL&from=0&to=$now")

        assertThat(tradesCode).isEqualTo(501)
        assertThat(tradesBody).contains("\"code\":\"unsupported\"").contains("trades")
        assertThat(liqCode).isEqualTo(501)
        assertThat(liqBody).contains("liquidations")
        assertThat(venue.tradeCalls).isEmpty()
        assertThat(venue.liquidationCalls).isEmpty()
    }

    @Test
    fun `a missing symbol or a bad time is a bad request naming it`() {
        start()

        assertThat(get("/v1/trades?from=0&to=10").second).contains("symbol missing")
        assertThat(get("/v1/liquidations?symbol=X&from=soon&to=10").second).contains("from must be epoch milliseconds")
    }
}
