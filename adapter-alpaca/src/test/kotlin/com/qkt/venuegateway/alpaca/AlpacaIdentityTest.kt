package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.alpaca.client.AlpacaData
import com.qkt.venuegateway.alpaca.client.AlpacaJsonHttp
import com.qkt.venuegateway.alpaca.client.AlpacaTrading
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The account is named by its account number; the API key that logs in never reaches anything the adapter reports. */
class AlpacaIdentityTest {
    private val keyId = "PKSENTINELKEYID0000000000"
    private val secret = "sentinel-secret-value"

    private fun adapter(): AlpacaAdapter {
        val http =
            FixtureHttp(
                mapOf(
                    "GET /v2/account" to listOf("account.json"),
                    "GET /v2/positions" to listOf("positions-crypto.json"),
                    "GET /v2/orders" to listOf("orders-open.json"),
                    "GET /v2/assets" to listOf("assets-crypto.json"),
                ),
            )
        return AlpacaAdapter(
            AlpacaSettings(AlpacaEnvironment.PAPER, assetClasses = setOf("crypto")),
            AlpacaTrading(http),
            AlpacaData(http, "iex"),
            { 0L },
            { _, _ ->
                object : AlpacaLink {
                    override fun start() {}

                    override fun close() {}
                }
            },
            { listing, _, _ -> AlpacaQuotes(listing, false) { _, _ -> error("no quotes in this test") } },
        )
    }

    @Test
    fun `the identity login is the account number, read once from the account`() {
        val identity = adapter().identity()
        assertThat(identity.login).isEqualTo(Fixtures.obj("account.json")["account_number"].toString().trim('"'))
        assertThat(identity.login).isEqualTo("PA0000000000")
    }

    @Test
    fun `nothing the adapter reports, nor its HTTP clients' names, carries the API key or its secret`() {
        val a = adapter()
        val reported = listOf(a.identity(), a.account(), a.positions(), a.openOrders(), a.instruments()).joinToString()
        assertThat(reported).doesNotContain(keyId).doesNotContain(secret)
        assertThat(AlpacaJsonHttp("https://paper-api.alpaca.markets", keyId, secret, okhttp3.OkHttpClient()).toString())
            .doesNotContain(keyId)
            .doesNotContain(secret)
    }
}
