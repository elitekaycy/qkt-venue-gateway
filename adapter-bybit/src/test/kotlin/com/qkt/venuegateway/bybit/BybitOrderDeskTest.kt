package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.bybit.client.BybitInstrument
import com.qkt.venuegateway.bybit.client.BybitMarketJson
import com.qkt.venuegateway.bybit.client.BybitPrivateClient
import com.qkt.venuegateway.bybit.client.BybitPublicClient
import com.qkt.venuegateway.bybit.client.BybitRest
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitOrderDeskTest {
    private val now = BybitVenue.NOW
    private val servers = mutableListOf<Fixtures.Server>()

    private fun server(route: (String, Map<String, String>) -> String?) =
        Fixtures.Server(route).also { servers += it }

    @AfterEach
    fun stop() = servers.forEach { it.close() }

    @Test
    fun `an order bybit took but does not list yet is unavailable after the read-backs, never answered as placed`() {
        val server =
            server { path, query ->
                if (path == "/v5/order/realtime") {
                    "private/order-realtime-unknown.json"
                } else {
                    BybitVenue.linearRoute(path, query)
                }
            }
        val account = BybitPrivateClient(BybitRest(server.url, "the-key", "the-secret", { now }), "linear", "USDT")
        val public = BybitPublicClient(BybitRest(server.url))
        val settings = bybitSettings(BybitVenue.LINEAR)
        val listing = BybitListing({ public.instruments("linear") }, "USDT", { now }, 600_000)
        val modes = BybitPositionModes(account::positionSlots, { "BTCUSDT" }, true, { now })
        val desk = BybitOrderDesk(account, settings, listing, modes, readBackTries = 3, pauseMs = 1)
        val order =
            NewOrder(
                "rec-rest-1",
                "BTCUSDT",
                Side.BUY,
                OrderType.MARKET,
                BigDecimal("0.001"),
                timeInForce = TimeInForce.IOC,
            )

        assertThatThrownBy { desk.place(order) }
            .isInstanceOf(VenueUnavailableException::class.java)
            .hasMessageContaining("rec-rest-1")
        assertThat(server.requests.count { it.url.encodedPath == "/v5/order/create" }).isEqualTo(1)
        assertThat(server.requests.count { it.url.encodedPath == "/v5/order/history" }).isEqualTo(3)
    }

    /**
     * Bybit's recorded wallet holds only USDT, so the recorded BTCUSDT pair is read here as the pair trading that
     * coin: what is under test is how a held coin becomes a position, not which coin it is.
     */
    @Test
    fun `a coin held is a position of the pair trading it, priced at the pair's last trade`() {
        val server =
            server { path, _ ->
                when (path) {
                    "/v5/account/wallet-balance" -> "private/wallet.json"
                    "/v5/market/tickers" -> "ticker-spot-BTCUSDT.json"
                    else -> null
                }
            }
        val pair: BybitInstrument =
            BybitMarketJson.instrument(BybitVenue.items("instruments-spot-BTCUSDT.json").first(), "spot")
        val listing = BybitListing({ listOf(pair.copy(baseCoin = "USDT")) }, "USDT", { now }, 600_000)
        val holdings =
            BybitSpotHoldings(
                BybitPrivateClient(BybitRest(server.url, "the-key", "the-secret", { now }), "spot", null),
                BybitPublicClient(BybitRest(server.url)),
                listing,
            )

        val rows = holdings.rows()

        assertThat(rows).containsExactly(PositionRow("BTCUSDT", BigDecimal("20000"), BigDecimal("85904.7")))
        val ticker = server.requests.single { it.url.encodedPath == "/v5/market/tickers" }
        assertThat(ticker.url.queryParameter("category")).isEqualTo("spot")
    }
}
