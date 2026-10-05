package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.bybit.client.BybitPublicClient
import com.qkt.venuegateway.bybit.client.BybitRest
import com.qkt.venuegateway.testkit.AdapterContractTest
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach

/**
 * The Bybit adapter (category `linear`) against the real testnet: real orders on BTCUSDT at its smallest size.
 * Runs only with `BYBIT_CLIENT_ID` and `BYBIT_CLIENT_SECRET` set to a testnet API key (the key and its secret).
 */
class BybitContractTest : AdapterContractTest() {
    private val key = System.getenv("BYBIT_CLIENT_ID")
    private val secret = System.getenv("BYBIT_CLIENT_SECRET")
    private val perp = "BTCUSDT"
    private val size = BigDecimal("0.001")
    private val market by lazy { BybitPublicClient(BybitRest(BybitEnvironment.TESTNET.restUrl)) }

    @BeforeEach
    fun testnetKey() = assumeTrue(!key.isNullOrBlank() && !secret.isNullOrBlank(), "no testnet key")

    /** A buy limit at 90% of the bid, on the tick: it rests. */
    private val farPrice by lazy {
        val tick = market.instruments("linear").first { it.symbol == perp }.tickSize
        val bid = market.ticker("linear", perp)?.bid ?: error("testnet $perp has no bid")
        bid.multiply(BigDecimal("0.9")).divide(tick, 0, RoundingMode.DOWN).multiply(tick)
    }

    override fun newAdapter(stateDir: Path): VenueAdapter =
        BybitAdapterFactory().create(
            AdapterContext(
                mapOf("environment" to "testnet", "category" to "linear"),
                System::currentTimeMillis,
                stateDir,
                Credentials(key, secret),
            ),
        )

    override fun restingOrder(clientOrderId: String) =
        NewOrder(clientOrderId, perp, Side.BUY, OrderType.LIMIT, size, farPrice, null, TimeInForce.GTC)

    override fun fillingOrder(clientOrderId: String) =
        NewOrder(clientOrderId, perp, Side.BUY, OrderType.MARKET, size, null, null, TimeInForce.GTC)

    override fun closingOrder(clientOrderId: String) =
        NewOrder(clientOrderId, perp, Side.SELL, OrderType.MARKET, size, null, null, TimeInForce.GTC, reduceOnly = true)

    /** Half a step over the minimum: Bybit refuses a quantity off its step (`Qty invalid`). */
    override fun refusedOrder(clientOrderId: String) =
        NewOrder(clientOrderId, perp, Side.BUY, OrderType.LIMIT, BigDecimal("0.0015"), farPrice, null, TimeInForce.GTC)

    override val activeCode = perp

    override val perpetualCode = perp
}
