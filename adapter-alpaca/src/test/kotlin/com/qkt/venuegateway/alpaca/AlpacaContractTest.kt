package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.alpaca.client.AlpacaJson
import com.qkt.venuegateway.alpaca.client.AlpacaJsonHttp
import com.qkt.venuegateway.support.JsonReads.decimal
import com.qkt.venuegateway.testkit.AdapterContractTest
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach

/**
 * The Alpaca adapter against the paper environment: real orders on one AAPL share, with `extended_hours` on, so the
 * suite passes in Alpaca's regular, extended and overnight sessions (Sunday 20:00 to Friday 20:00 New York); a
 * weekend run cannot fill a share. Bars and quotes are checked on BTCUSD, which trades all week,
 * so the suite enables coin pairs itself. Runs only with
 * `ALPACA_CLIENT_ID` and `ALPACA_CLIENT_SECRET` set to a paper API key (its id and secret).
 */
class AlpacaContractTest : AdapterContractTest() {
    private val key = System.getenv("ALPACA_CLIENT_ID")
    private val secret = System.getenv("ALPACA_CLIENT_SECRET")
    private val share = "AAPL"
    private val one = BigDecimal.ONE

    @BeforeEach
    fun paperKey() = assumeTrue(!key.isNullOrBlank() && !secret.isNullOrBlank(), "no paper key")

    /** AAPL's ask now: the overnight book's from 20:00 to 04:00 New York, the IEX book's otherwise. */
    private val ask by lazy { quote("ap") }
    private val bid by lazy { quote("bp") }

    private fun quote(side: String): BigDecimal {
        val http = AlpacaJsonHttp("https://data.alpaca.markets", key, secret, okhttp3.OkHttpClient())
        return listOf("overnight", "iex")
            .mapNotNull { feed ->
                val answer =
                    AlpacaJson.obj(
                        http.get("/v2/stocks/$share/quotes/latest", listOf("feed" to feed)),
                        "quote",
                    )
                (answer["quote"] as? JsonObject)?.decimal(side)?.takeIf { it.signum() > 0 }
            }.firstOrNull() ?: error("no $share $side on any feed")
    }

    private fun cents(price: BigDecimal) = price.setScale(2, RoundingMode.HALF_UP)

    override fun newAdapter(stateDir: Path): VenueAdapter =
        AlpacaAdapterFactory().create(
            AdapterContext(
                mapOf("environment" to "paper", "extended_hours" to "true", "asset_classes" to "us_equity,crypto"),
                System::currentTimeMillis,
                stateDir,
                Credentials(key, secret),
            ),
        )

    /** A buy at 60% of the ask: it rests. */
    override fun restingOrder(clientOrderId: String) =
        NewOrder(
            clientOrderId,
            share,
            Side.BUY,
            OrderType.LIMIT,
            one,
            cents(ask * BigDecimal("0.6")),
            null,
            TimeInForce.DAY,
        )

    /** A buy 3% through the ask: extended-hours orders must be limits, and this one fills at once. */
    override fun fillingOrder(clientOrderId: String) =
        NewOrder(
            clientOrderId,
            share,
            Side.BUY,
            OrderType.LIMIT,
            one,
            cents(ask * BigDecimal("1.03")),
            null,
            TimeInForce.DAY,
        )

    override fun closingOrder(clientOrderId: String) =
        NewOrder(
            clientOrderId,
            share,
            Side.SELL,
            OrderType.LIMIT,
            one,
            cents(bid * BigDecimal("0.97")),
            null,
            TimeInForce.DAY,
            reduceOnly = true,
        )

    /** A sub-penny limit on a share above $1, which Alpaca refuses. */
    override fun refusedOrder(clientOrderId: String) =
        NewOrder(clientOrderId, share, Side.BUY, OrderType.LIMIT, one, BigDecimal("1.005"), null, TimeInForce.DAY)

    override val activeCode = "BTCUSD"
}
