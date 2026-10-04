package com.qkt.venuegateway.deribit.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The settlement history paged as Deribit pages it, from three pages recorded on testnet two rows at a time. */
class DeribitHistoriesTest {
    private val asked = mutableListOf<JsonObject>()

    private fun recorded(name: String) =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/private/$name")!!.readText()).jsonObject

    /** Answers each request with the recorded page whose request carried the same continuation. */
    private val pages =
        (1..3).map { recorded("settlement-history-page-$it.json") }.associateBy {
            it["params"]!!.jsonObject["continuation"]?.jsonPrimitive?.content
        }
    private val histories =
        DeribitHistories { method, params ->
            assertThat(method).isEqualTo("private/get_settlement_history_by_currency")
            asked += params
            val page = pages.getValue(params["continuation"]?.jsonPrimitive?.content)
            page["response"]!!.jsonObject["result"] as JsonElement
        }

    @Test
    fun `every page is followed by its continuation from the window's end, and the rows come back oldest first`() {
        val rows = histories.settlements("USDC", 1_790_928_001_112L, 1_791_014_400_571L)

        assertThat(asked).hasSize(3)
        assertThat(asked.map { it["search_start_timestamp"]!!.jsonPrimitive.content }).containsOnly("1791014400571")
        assertThat(asked.map { it["currency"]!!.jsonPrimitive.content }).containsOnly("USDC")
        assertThat(rows.map { it.timestampMs }).isSorted
        assertThat(rows.map { it.type to it.instrument }).containsExactlyInAnyOrder(
            "settlement" to "BTC_USDC-PERPETUAL",
            "exercise" to "TRX_USDC-2OCT26-0d342-C",
            "settlement" to "BTC_USDC-30OCT26",
            "settlement" to "BTC_USDC-PERPETUAL",
        )
    }

    @Test
    fun `paging stops once a page reaches back past the window, whose older rows are left out`() {
        val rows = histories.settlements("USDC", 1_790_928_001_113L, 1_791_014_400_571L)

        assertThat(asked).hasSize(2)
        assertThat(rows.map { it.instrument }).containsExactlyInAnyOrder("BTC_USDC-30OCT26", "BTC_USDC-PERPETUAL")
    }

    @Test
    fun `the last page's continuation is the word none, read as no further page`() {
        val (rows, next) =
            DeribitPrivateJson.settlementPage(
                recorded("settlement-history-page-3.json")["response"]!!.jsonObject["result"]!!.jsonObject,
            )

        assertThat(rows).isEmpty()
        assertThat(next).isNull()
    }
}
