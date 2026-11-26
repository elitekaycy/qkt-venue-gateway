package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.alpaca.client.AlpacaHttp
import com.qkt.venuegateway.alpaca.client.AlpacaJson
import com.qkt.venuegateway.alpaca.client.AlpacaTrading
import java.math.BigDecimal

/** An order desk over recorded answers: the coin pairs, AAPL, an unknown share, and the [routes] a test adds. */
object DeskFixture {
    /** The recorded answers every desk needs: the coin pairs, AAPL, MSFT and an unknown share. */
    val assets =
        mapOf(
            "GET /v2/assets" to listOf("assets-crypto.json"),
            "GET /v2/assets/AAPL" to listOf("asset-AAPL.json"),
            "GET /v2/assets/MSFT" to listOf("asset-MSFT.json"),
            "GET /v2/assets/NOPE" to listOf("404:asset-unknown.json"),
        )

    fun desk(
        routes: Map<String, List<String>>,
        extendedHours: Boolean = false,
        held: BigDecimal = BigDecimal.ZERO,
        fractional: Boolean = false,
        clock: () -> Long = { 0L },
    ): Pair<AlpacaOrderDesk, FixtureHttp> = desk(FixtureHttp(assets + routes), extendedHours, held, fractional, clock)

    /** An order desk over [http] itself, for a test that alters a recorded answer ([EditedHttp]). */
    fun desk(
        http: FixtureHttp,
        extendedHours: Boolean = false,
        held: BigDecimal = BigDecimal.ZERO,
        fractional: Boolean = false,
        clock: () -> Long = { 0L },
        alpaca: AlpacaHttp = http,
    ): Pair<AlpacaOrderDesk, FixtureHttp> {
        val trading = AlpacaTrading(alpaca)
        val settings = AlpacaSettings(AlpacaEnvironment.PAPER, extendedHours = extendedHours, fractional = fractional)
        val listing =
            AlpacaListing(
                trading,
                linkedSetOf(AlpacaSettings.EQUITY, AlpacaSettings.CRYPTO),
                { 0L },
                fractional = fractional,
            )
        return AlpacaOrderDesk(trading, listing, settings, { held }, clock, {}) to http
    }

    /** The JSON body of the last order the desk sent. */
    fun body(http: FixtureHttp) = AlpacaJson.obj(http.sent.last { it.method != "GET" }.body!!, "sent")
}
