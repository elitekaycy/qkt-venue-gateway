package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.deribit.client.DeribitPublicClient
import com.qkt.venuegateway.testkit.AdapterContractTest
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach

/**
 * The Deribit adapter against the real testnet: real orders on BTC_USDC-PERPETUAL at its smallest
 * size. Runs only with `DERIBIT_CLIENT_ID` and `DERIBIT_CLIENT_SECRET` set to a testnet API key.
 * Market orders are GTC, as qkt sends them: Deribit refuses IOC and FOK on market and stop orders.
 */
class DeribitContractTest : AdapterContractTest() {
    private val clientId = System.getenv("DERIBIT_CLIENT_ID")
    private val clientSecret = System.getenv("DERIBIT_CLIENT_SECRET")
    private val perp = "BTC_USDC-PERPETUAL"
    private val size = BigDecimal("0.0001")

    @BeforeEach
    fun testnetKey() = assumeTrue(!clientId.isNullOrBlank() && !clientSecret.isNullOrBlank(), "no testnet key")

    /** A buy limit at 60% of the bid, on the tick: it rests. */
    private val farPrice by lazy {
        val market = DeribitPublicClient(DeribitEnvironment.TESTNET.httpUrl)
        val tick = market.instrument(perp).tickSize
        val bid = market.ticker(perp).bid ?: error("testnet $perp has no bid")
        bid.multiply(BigDecimal("0.6")).divide(tick, 0, RoundingMode.DOWN).multiply(tick)
    }

    override fun newAdapter(stateDir: Path): VenueAdapter =
        DeribitAdapterFactory().create(
            AdapterContext(
                mapOf("environment" to "testnet"),
                System::currentTimeMillis,
                stateDir,
                Credentials(clientId, clientSecret),
            ),
        )

    override fun restingOrder(clientOrderId: String) =
        NewOrder(clientOrderId, perp, Side.BUY, OrderType.LIMIT, size, farPrice, null, TimeInForce.GTC)

    override fun fillingOrder(clientOrderId: String) =
        NewOrder(clientOrderId, perp, Side.BUY, OrderType.MARKET, size, null, null, TimeInForce.GTC)

    override fun closingOrder(clientOrderId: String) =
        NewOrder(clientOrderId, perp, Side.SELL, OrderType.MARKET, size, null, null, TimeInForce.GTC, reduceOnly = true)

    /** Half a step: Deribit refuses amounts off its contract size. */
    override fun refusedOrder(clientOrderId: String) =
        NewOrder(clientOrderId, perp, Side.BUY, OrderType.LIMIT, BigDecimal("0.00015"), farPrice, null, TimeInForce.GTC)

    /**
     * A market buy one step larger than every ask inside Deribit's price band on the first thin stock
     * perpetual (testnet quotes them a hundredth a level), worth at most [OVERRUN_LIMIT] dollars; null when
     * none is that thin now. Deribit refuses IOC on market orders, so only the adapter keeps it from resting.
     */
    override fun overrunOrder(clientOrderId: String): NewOrder? {
        val market = DeribitPublicClient(DeribitEnvironment.TESTNET.httpUrl)
        return THIN.firstNotNullOfOrNull { code ->
            val book = Json.parseToJsonElement(URI("$BOOK$code").toURL().readText()).jsonObject["result"]!!.jsonObject
            val band = BigDecimal(book["max_price"]!!.jsonPrimitive.content)
            val asks =
                book["asks"]!!
                    .jsonArray
                    .map { it.jsonArray.map { n -> BigDecimal(n.jsonPrimitive.content) } }
                    .filter { (price) -> price <= band }
            val amount = asks.sumOf { it[1] } + market.instrument(code).minTradeAmount
            NewOrder(clientOrderId, code, Side.BUY, OrderType.MARKET, amount, null, null, TimeInForce.GTC)
                .takeIf { asks.isNotEmpty() && amount * band <= OVERRUN_LIMIT }
        }
    }

    override val activeCode = perp

    override val perpetualCode = perp

    /** The BTC option expiring soonest at least a day out, struck nearest the perpetual's bid: BTC is active on testnet. */
    override val optionCode by lazy {
        val market = DeribitPublicClient(DeribitEnvironment.TESTNET.httpUrl)
        val bid = market.ticker(perp).bid ?: error("testnet $perp has no bid")
        val after = System.currentTimeMillis() + DAY_MS
        market
            .instruments("USDC", "option")
            .filter { it.name.startsWith("BTC_USDC-") && (it.expiryMs ?: 0) > after }
            .minWith(compareBy({ it.expiryMs }, { (it.strike ?: bid).subtract(bid).abs() }))
            .name
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val BOOK = "https://test.deribit.com/api/v2/public/get_order_book?depth=50&instrument_name="
        val THIN = listOf("AAPL_USDC-PERPETUAL", "AMZN_USDC-PERPETUAL", "AMD_USDC-PERPETUAL")
        val OVERRUN_LIMIT = BigDecimal("50")
    }
}
