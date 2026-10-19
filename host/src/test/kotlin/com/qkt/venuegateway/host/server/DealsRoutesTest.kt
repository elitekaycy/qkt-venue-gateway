package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import com.qkt.vgp.WireDeals
import com.qkt.vgp.WireFill
import com.qkt.vgp.WireSettlement
import com.qkt.vgp.WireSettlements
import java.nio.file.Path
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DealsRoutesTest {
    @TempDir lateinit var dir: Path
    private val venue = FakeAdapter()
    private val http = OkHttpClient()
    private val hour = 3_600_000L
    private var now = 5_000 * hour
    private lateinit var gateway: Gateway
    private lateinit var hub: QuoteHub
    private lateinit var server: GatewayServer
    private var base = ""

    @BeforeEach
    fun start() {
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

    private fun fill(
        fillId: String,
        time: Long,
    ) = WireFill("a-1", "v-a-1", fillId, "BTC_USDC-PERPETUAL", "buy", "0.1", "84000", time)

    private fun deals(path: String) = wireJson.decodeFromString(WireDeals.serializer(), get(path).second).deals

    @Test
    fun `deals are served for the window from and to, oldest first`() {
        gateway.journal.appendFill(fill("f-2", now - hour))
        gateway.journal.appendFill(fill("f-1", now - 3 * hour))
        gateway.journal.appendFill(fill("f-0", now - 9 * hour))

        assertThat(deals("/v1/deals?from=${now - 4 * hour}&to=$now").map { it.fillId }).containsExactly("f-1", "f-2")
    }

    @Test
    fun `deals without to run up to the gateway's time, and later fills wait for the next read`() {
        gateway.journal.appendFill(fill("f-1", now - hour))
        gateway.journal.appendFill(fill("f-later", now + hour))

        assertThat(deals("/v1/deals?from=${now - 2 * hour}").map { it.fillId }).containsExactly("f-1")
        now += 2 * hour
        assertThat(deals("/v1/deals?from=${now - 4 * hour}").map { it.fillId }).containsExactly("f-1", "f-later")
    }

    @Test
    fun `a window needs from, and a to that is not a number is refused by name`() {
        val (missing, missingBody) = get("/v1/deals?to=$now")
        val (bad, badBody) = get("/v1/deals?from=0&to=soon")

        assertThat(missing).isEqualTo(400)
        assertThat(missingBody).contains("from must be epoch milliseconds")
        assertThat(bad).isEqualTo(400)
        assertThat(badBody).contains("to must be epoch milliseconds")
    }

    @Test
    fun `settlements without to run up to the gateway's time`() {
        gateway.journal.appendSettlement(WireSettlement("BTC-27SEP26", "84000", now - hour))
        gateway.journal.appendSettlement(WireSettlement("BTC-25DEC26", "90000", now + hour))

        val (code, body) = get("/v1/settlements?from=0")

        assertThat(code).isEqualTo(200)
        val settled = wireJson.decodeFromString(WireSettlements.serializer(), body).settlements
        assertThat(settled.map { it.symbol }).containsExactly("BTC-27SEP26")
    }
}
