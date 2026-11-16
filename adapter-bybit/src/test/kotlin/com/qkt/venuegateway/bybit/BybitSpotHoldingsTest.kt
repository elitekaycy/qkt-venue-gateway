package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.bybit.client.BybitPrivateClient
import com.qkt.venuegateway.bybit.client.BybitPublicClient
import com.qkt.venuegateway.bybit.client.BybitRest
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitSpotHoldingsTest {
    private val wallet = firstRecord("private/wallet.json")
    private val usdt = wallet["coin"]!!.jsonArray.first().jsonObject
    private var coins = listOf("BTC" to "0.5", "USDT" to "20000")
    private var ticker = Fixtures.text("ticker-spot-BTCUSDT.json")
    private val server =
        Canned { path, _ ->
            when (path) {
                "/v5/account/wallet-balance" -> answerWith("private/wallet.json", listOf(walletHolding()))
                "/v5/market/instruments-info" -> Fixtures.text("instruments-spot-BTCUSDT.json")
                "/v5/market/tickers" -> ticker
                else -> null
            }
        }
    private val holdings =
        BybitSpotHoldings(
            BybitPrivateClient(BybitRest(server.url, "k", "s"), "spot", null),
            BybitPublicClient(BybitRest(server.url)),
            BybitListing(
                { BybitPublicClient(BybitRest(server.url)).instruments("spot") },
                "USDT",
                { 0L },
                600_000,
            ),
        )

    @AfterEach
    fun stop() = server.close()

    private fun walletHolding(): JsonObject {
        val rows = coins.map { (coin, held) -> usdt.with("coin" to coin, "walletBalance" to held) }
        return JsonObject(wallet + ("coin" to JsonArray(rows)))
    }

    @Test
    fun `a held coin is a position in its listed pair at the pair's last price`() {
        val rows = holdings.rows()

        assertThat(rows.map { it.symbol }).containsExactly("BTCUSDT")
        assertThat(rows.single().quantity).isEqualTo(BigDecimal("0.5"))
        assertThat(rows.single().avgPrice).isEqualTo(BigDecimal("85904.7"))
    }

    @Test
    fun `a coin with no listed pair and a coin held at zero are no position`() {
        coins = listOf("BTC" to "0", "DOGE" to "3", "USDT" to "20000")

        assertThat(holdings.rows()).isEmpty()
    }

    @Test
    fun `a held pair bybit gives no ticker for, or no last price, is refused by name`() {
        ticker = answerWith("ticker-spot-BTCUSDT.json", emptyList())
        assertThatThrownBy { holdings.rows() }.hasMessageContaining("no last price of BTCUSDT")

        ticker =
            answerWith(
                "ticker-spot-BTCUSDT.json",
                listOf(
                    firstRecord("ticker-spot-BTCUSDT.json").with(
                        "lastPrice" to null,
                    ),
                ),
            )
        assertThatThrownBy { holdings.rows() }.hasMessageContaining("no last price of BTCUSDT")
    }
}
