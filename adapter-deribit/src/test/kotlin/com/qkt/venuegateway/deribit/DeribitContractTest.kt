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
import java.nio.file.Path
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

    override val activeCode = perp
}
