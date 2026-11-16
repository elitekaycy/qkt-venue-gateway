package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.bybit.client.BybitPrivateClient
import com.qkt.venuegateway.bybit.client.BybitRest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitPrivateClientArmsTest {
    private val wallet = firstRecord("private/wallet.json")
    private var accounts: List<JsonObject> = listOf(wallet)
    private val server = Canned { _, _ -> answerWith("private/wallet.json", accounts) }
    private val client = BybitPrivateClient(BybitRest(server.url, "k", "s"), "linear", "USDT")

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `an answer with no unified wallet is refused by name, for the account and for its coins`() {
        accounts = emptyList()

        assertThatThrownBy { client.wallet() }.hasMessageContaining("no unified wallet")
        assertThatThrownBy { client.coins() }.hasMessageContaining("no unified wallet")
    }

    @Test
    fun `a coin with no wallet balance is held at zero`() {
        val coin = wallet["coin"]!!.jsonArray.first().jsonObject
        accounts = listOf(JsonObject(wallet + ("coin" to JsonArray(listOf(coin.with("walletBalance" to null))))))

        assertThat(client.coins()).containsEntry("USDT", java.math.BigDecimal.ZERO)
    }
}
