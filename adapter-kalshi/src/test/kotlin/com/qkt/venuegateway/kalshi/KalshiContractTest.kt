package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.kalshi.client.KalshiPublic
import com.qkt.venuegateway.kalshi.client.KalshiRest
import com.qkt.venuegateway.testkit.AdapterContractTest
import java.math.BigDecimal
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach

/**
 * The Kalshi adapter against the demo environment: real orders on the market of Kalshi's demo hourly series open now
 * (`KXDEMOFUTHOURLY`, shard 0), the one demo market a maker keeps bid. Runs only with `KALSHI_CLIENT_ID` (the API
 * key id) and `KALSHI_CLIENT_SECRET` (its PEM private key) set to a demo key on a funded demo account.
 */
class KalshiContractTest : AdapterContractTest() {
    private val id = System.getenv("KALSHI_CLIENT_ID")
    private val secret = System.getenv("KALSHI_CLIENT_SECRET")
    private val one = BigDecimal.ONE

    @BeforeEach
    fun demoKey() = assumeTrue(!id.isNullOrBlank() && !secret.isNullOrBlank(), "no demo key")

    private val hourly by lazy {
        val public = KalshiPublic(KalshiRest(KalshiEnvironment.DEMO.restUrl, timeoutMs = 60_000))
        public.openEvents().flatMap { it.markets }.first {
            it.ticker.startsWith("KXDEMOFUTHOURLY") && it.status == "active"
        }
    }

    override fun newAdapter(stateDir: Path): VenueAdapter =
        KalshiAdapterFactory().create(
            AdapterContext(
                mapOf("environment" to "demo"),
                System::currentTimeMillis,
                stateDir,
                Credentials(id, secret),
            ),
        )

    /** A buy at the lowest cent: it rests. */
    override fun restingOrder(clientOrderId: String) = limit(clientOrderId, "0.01")

    /** A market sell into the maker's bid. */
    override fun fillingOrder(clientOrderId: String) =
        NewOrder(clientOrderId, hourly.ticker, Side.SELL, OrderType.MARKET, one, null, null, TimeInForce.GTC)

    override fun closingOrder(clientOrderId: String) =
        NewOrder(clientOrderId, hourly.ticker, Side.BUY, OrderType.MARKET, one, null, null, TimeInForce.GTC, true)

    /** A price between cents on a whole-cent market: Kalshi refuses it (`400 invalid_price`). */
    override fun refusedOrder(clientOrderId: String) = limit(clientOrderId, "0.015")

    override val activeCode: String get() = hourly.ticker

    private fun limit(
        clientOrderId: String,
        price: String,
    ) = NewOrder(clientOrderId, hourly.ticker, Side.BUY, OrderType.LIMIT, one, BigDecimal(price), null, TimeInForce.GTC)
}
