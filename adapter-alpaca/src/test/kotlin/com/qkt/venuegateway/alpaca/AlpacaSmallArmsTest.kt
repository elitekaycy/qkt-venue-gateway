package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.alpaca.client.AlpacaData
import com.qkt.venuegateway.alpaca.client.AlpacaTrading
import java.math.BigDecimal
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AlpacaSmallArmsTest {
    private val buy: VenueFill = AlpacaMapping.fill(Fixtures.array("activities-fill.json").first(), "l")

    private fun fees(after: BigDecimal): VenueFill =
        AlpacaCoinFees { emptyList() }.apply { seed() }.received(buy, after, "BTC")

    @Test
    fun `a coin buy is net of a fee up to one percent, and beyond it, or against a stale position, is as filled`() {
        val q = buy.quantity

        assertThat(fees(q).costs).describedAs("nothing kept").isEmpty()
        assertThat(fees(q.multiply(BigDecimal("0.995"))).costs.single().amount)
            .isEqualByComparingTo(q.multiply(BigDecimal("0.005")))
        assertThat(fees(q.multiply(BigDecimal("0.5"))).costs).describedAs("half kept").isEmpty()
        assertThat(fees(q.multiply(BigDecimal("2"))).costs).describedAs("more arrived than bought").isEmpty()
        assertThat(fees(BigDecimal.ZERO).costs).describedAs("the position did not move").isEmpty()
    }

    @Test
    fun `a position of no known size leaves the fill as it is`() {
        assertThat(AlpacaCoinFees { emptyList() }.received(buy, null, "BTC")).isEqualTo(buy)
    }

    @Test
    fun `asset classes naming nothing are refused`() {
        val context =
            AdapterContext(mapOf("environment" to "paper", "asset_classes" to " , "), { 0L }, Path.of("unused"))

        assertThatThrownBy { AlpacaSettings.of(context) }.hasMessageContaining("asset_classes")
    }

    @Test
    fun `a label's next generation counts from none, and from the one it carries`() {
        assertThat(AlpacaLabels.next("qkt-1")).isEqualTo("qkt-1~r1")
        assertThat(AlpacaLabels.next("qkt-1~r1")).isEqualTo("qkt-1~r2")
        assertThat(AlpacaLabels.next("qkt-1~r9")).isEqualTo("qkt-1~r10")
        assertThat(AlpacaLabels.labelOf("qkt-1~r10")).isEqualTo("qkt-1")
        assertThat(AlpacaLabels.labelOf("qkt~rx")).isEqualTo("qkt~rx")
    }

    @Test
    fun `streamed fills older than the keep time are forgotten, and a streamed copy wins over a listed one`() {
        var now = 0L
        val recent = AlpacaRecentFills({ now }, keepMs = 1_000)
        val old = buy.copy(fillId = "old", timeMs = 5)
        val fresh = buy.copy(fillId = "fresh", timeMs = 10)
        recent.add(old)
        now = 2_000
        recent.add(fresh)

        val merged = recent.merged(listOf(buy.copy(fillId = "fresh", price = BigDecimal("1")), old), 0, 100)

        assertThat(merged.map { it.fillId }).containsExactly("old", "fresh")
        assertThat(merged.last().price).isEqualTo(buy.price)
        assertThat(recent.merged(emptyList(), 0, 100).map { it.fillId }).containsExactly("fresh")
        assertThat(recent.merged(emptyList(), 11, 100)).isEmpty()
    }

    private fun listing(
        classes: Set<String>,
        routes: Map<String, List<String>>,
        clock: () -> Long = { 0L },
    ): Pair<AlpacaListing, FixtureHttp> {
        val http = FixtureHttp(routes)
        return AlpacaListing(AlpacaTrading(http), classes, clock, refreshMs = 1_000) to http
    }

    @Test
    fun `a share is looked up once while fresh and again when stale, and none is looked up for a coin-only account`() {
        var now = 0L
        val routes =
            mapOf(
                "GET /v2/assets/AAPL" to listOf("asset-AAPL.json"),
                "GET /v2/assets" to listOf("assets-crypto.json"),
            )
        val (both, http) = listing(setOf("us_equity", "crypto"), routes, { now })

        assertThat(both.find("AAPL")).isNotNull
        assertThat(both.find("AAPL")).isNotNull
        assertThat(http.sent.count { it.path == "/v2/assets/AAPL" }).isEqualTo(1)
        now = 5_000
        assertThat(both.find("AAPL")).isNotNull
        assertThat(http.sent.count { it.path == "/v2/assets/AAPL" }).isEqualTo(2)

        val (coins, coinHttp) = listing(setOf("crypto"), routes)
        assertThat(coins.find("AAPL")).isNull()
        assertThat(coinHttp.sent.none { it.path == "/v2/assets/AAPL" }).isTrue
        assertThat(coins.all().map { it.instrument.code }).contains("BTCUSD")
    }

    @Test
    fun `an account listing shares only reads no coin pairs, and one listing coins only reads no shares`() {
        val routes = mapOf("GET /v2/assets" to listOf("assets-us-equity.json"))
        val (shares, http) = listing(setOf("us_equity"), routes)

        val all = shares.all()

        assertThat(all).isNotEmpty
        assertThat(all.none { it.crypto }).isTrue
        assertThat(http.sent.map { it.params.toMap()["asset_class"] }).containsExactly("us_equity")
    }

    @Test
    fun `an asset that is not tradable is listed as disabled and an unknown share is refused`() {
        val routes = mapOf("GET /v2/assets/NOPE" to listOf("404:asset-unknown.json"))
        val (listing, _) = listing(setOf("us_equity"), routes)

        assertThat(listing.find("NOPE")).isNull()
        assertThatThrownBy { listing.require("NOPE") }.hasMessageContaining("lists no NOPE")
    }

    @Test
    fun `a bar window is named by its largest whole unit, and one alpaca has no name for is refused`() {
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour

        assertThat(AlpacaTimeframes.of(day)).isEqualTo("1Day")
        assertThat(AlpacaTimeframes.of(7 * day)).isEqualTo("1Week")
        assertThat(AlpacaTimeframes.of(2 * hour)).isEqualTo("2Hour")
        assertThat(AlpacaTimeframes.of(23 * hour)).isEqualTo("23Hour")
        assertThat(AlpacaTimeframes.of(15 * minute)).isEqualTo("15Min")
        assertThatThrownBy { AlpacaTimeframes.of(2 * day) }.hasMessageContaining("no bars")
        assertThatThrownBy { AlpacaTimeframes.of(24 * hour + hour) }.hasMessageContaining("no bars")
        assertThatThrownBy { AlpacaTimeframes.of(90 * 1_000L) }.hasMessageContaining("no bars")
        assertThatThrownBy { AlpacaTimeframes.of(0) }.hasMessageContaining("no bars")
        assertThatThrownBy { AlpacaTimeframes.of(-hour) }.hasMessageContaining("no bars")
        assertThatThrownBy { AlpacaTimeframes.of(25 * hour) }.hasMessageContaining("no bars")
    }

    private fun equityAdapter(
        routes: Map<String, List<String>>,
    ): Triple<AlpacaAdapter, FixtureHttp, (JsonObject) -> Unit> {
        val http = FixtureHttp(routes)
        var update: (JsonObject) -> Unit = {}
        val adapter =
            AlpacaAdapter(
                AlpacaSettings(AlpacaEnvironment.PAPER, assetClasses = setOf("us_equity")),
                AlpacaTrading(http),
                AlpacaData(http, "iex"),
                { 0L },
                { onUpdate, _ ->
                    update = onUpdate
                    object : AlpacaLink {
                        override fun start() {}

                        override fun close() {}
                    }
                },
                { listing, _, _ -> AlpacaQuotes(listing, false) { _, _ -> error("no sockets") } },
            )
        return Triple(adapter, http) { update(it) }
    }

    private val sharesRoutes =
        mapOf(
            "GET /v2/account" to listOf("account.json"),
            "GET /v2/assets/AAPL" to listOf("asset-AAPL.json"),
            "GET /v2/assets/NOPE" to listOf("404:asset-unknown.json"),
        )

    @Test
    fun `the account number is read once and kept`() {
        val (adapter, http, _) = equityAdapter(sharesRoutes)

        val first = adapter.identity()
        val second = adapter.identity()

        assertThat(second).isEqualTo(first)
        assertThat(http.sent.count { it.path == "/v2/account" }).isEqualTo(1)
    }

    @Test
    fun `a share's fill is reported as filled, and a fill of an unlisted symbol is too, neither with a coin fee`() {
        val (adapter, _, push) = equityAdapter(sharesRoutes)
        val heard = HeardListener().also(adapter::connect)
        val fill = Fixtures.updates("ws-trade-updates-fill.json").last { AlpacaMapping.fill(it) != null }
        val order = fill["order"] as JsonObject

        push(JsonObject(fill + ("order" to order.with("symbol" to "AAPL"))))
        push(JsonObject(fill + ("order" to order.with("symbol" to "NOPE"))))

        assertThat(heard.fills.map { it.symbol }).containsExactly("AAPL", "NOPE")
        assertThat(heard.fills.flatMap { it.costs }).isEmpty()
        assertThat(heard.orders).hasSize(2)
    }
}
