package com.qkt.venuegateway.deribit.client

import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Each private call against a Deribit socket answering with the testnet recordings, and what each call sent. */
class DeribitPrivateCallsTest {
    private val venue = RecordedDeribitSocket()
    private val client = venue.connected()

    @AfterEach
    fun stop() {
        client.close()
        venue.close()
    }

    private fun JsonObject.params() = this["params"]!!.jsonObject

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    private fun recordedParams(name: String) = venue.recorded(name).params()

    @Test
    fun `positions are asked for the account's currency and read with both of their sizes`() {
        venue.answers["private/get_positions"] = "positions-future-linear.json"

        val position = client.positions("USDC").single()

        assertThat(venue.next("private/get_positions").params().text("currency")).isEqualTo("USDC")
        assertThat(position.instrument).isEqualTo("BTC_USDC-PERPETUAL")
        assertThat(position.size).isEqualByComparingTo("8.406277")
        assertThat(position.sizeCurrency).isEqualByComparingTo("0.0001")
    }

    @Test
    fun `open orders and the orders under one label are asked for exactly as recorded`() {
        venue.answers["private/get_open_orders_by_currency"] = "open-orders-with-stops.json"
        venue.answers["private/get_order_state_by_label"] = "order-state-by-label-open.json"

        val open = client.openOrders("USDC")
        val openAsked = venue.next("private/get_open_orders_by_currency").params()
        val labelled = client.ordersByLabel("USDC", "kit-probe-1790866831")
        val labelAsked = venue.next("private/get_order_state_by_label").params()

        assertThat(openAsked).isEqualTo(recordedParams("open-orders-with-stops.json"))
        assertThat(labelAsked).isEqualTo(recordedParams("order-state-by-label-open.json"))
        assertThat(open.map { it.state }).containsExactly("open", "untriggered", "untriggered")
        assertThat(open.map { it.orderType }).containsExactly("limit", "stop_limit", "stop_market")
        assertThat(labelled.single().label).isEqualTo("kit-probe-1790866831")
        assertThat(labelled.single().state).isEqualTo("open")
    }

    @Test
    fun `a cancel by label is sent as recorded and answers how many orders it cancelled`() {
        venue.answers["private/cancel_by_label"] = "cancel-by-label-one.json"

        val cancelled = client.cancelByLabel("USDC", "kit-probe-1790866831")

        val sent = venue.next("private/cancel_by_label").params()
        assertThat(sent).isEqualTo(recordedParams("cancel-by-label-one.json"))
        assertThat(cancelled).isEqualTo(1)
    }

    @Test
    fun `an edit by label sends the new amount and price as exact numbers and reads back the edited order`() {
        venue.answers["private/edit_by_label"] = "edit-by-label.json"

        val edited =
            client.editByLabel(
                "kit-stop-1790867163-d",
                "BTC_USDC-PERPETUAL",
                BigDecimal("0.0002"),
                BigDecimal("50497.5"),
                null,
            )

        val sent = venue.next("private/edit_by_label").params()
        assertThat(sent).isEqualTo(recordedParams("edit-by-label.json"))
        assertThat(edited.amount).isEqualByComparingTo("0.0002")
        assertThat(edited.price).isEqualByComparingTo("50497.5")
        assertThat(edited.state).isEqualTo("open")
    }

    @Test
    fun `an edit of a stop's trigger alone sends the trigger price and no limit price`() {
        venue.answers["private/edit_by_label"] = "edit-by-label.json"

        client.editByLabel(
            "kit-stop-1790867163-b",
            "BTC_USDC-PERPETUAL",
            BigDecimal("0.0001"),
            null,
            BigDecimal("126268.8"),
        )

        val sent = venue.next("private/edit_by_label").params()
        assertThat(sent["trigger_price"].toString()).isEqualTo("126268.8")
        assertThat(sent["amount"].toString()).isEqualTo("0.0001")
        assertThat(sent).doesNotContainKey("price")
    }

    @Test
    fun `a sell stop goes out as a sell carrying its trigger price and trigger, and no limit price`() {
        venue.answers["private/sell"] = "buy-stop-market-untriggered.json"

        val order =
            client.place(
                DeribitNewOrder(
                    "kit-stop-sell",
                    "BTC_USDC-PERPETUAL",
                    "sell",
                    "stop_market",
                    BigDecimal("0.0001"),
                    null,
                    BigDecimal("126268.8"),
                    "last_price",
                    "good_til_cancelled",
                    true,
                ),
            )

        val sent = venue.next("private/sell").params()
        assertThat(sent.text("type")).isEqualTo("stop_market")
        assertThat(sent["trigger_price"].toString()).isEqualTo("126268.8")
        assertThat(sent.text("trigger")).isEqualTo("last_price")
        assertThat(sent.text("reduce_only")).isEqualTo("true")
        assertThat(sent).doesNotContainKey("price")
        assertThat(order.orderType).isEqualTo("stop_market")
        assertThat(order.state).isEqualTo("untriggered")
    }

    @Test
    fun `trades are read from the window's start, oldest first, in pages of a thousand`() {
        venue.answers["private/get_user_trades_by_currency_and_time"] = "user-trades-by-time.json"

        val trades = client.trades("USDC", 1_790_866_864_590L, 1_790_866_877_110L)

        val sent = venue.next("private/get_user_trades_by_currency_and_time").params()
        assertThat(sent.text("currency")).isEqualTo("USDC")
        assertThat(sent.text("start_timestamp")).isEqualTo("1790866864590")
        assertThat(sent.text("end_timestamp")).isEqualTo("1790866877110")
        assertThat(sent.text("count")).isEqualTo("1000")
        assertThat(sent.text("sorting")).isEqualTo("asc")
        assertThat(trades.map { it.tradeId }).containsExactly("USDC-55455987", "USDC-55455988")
    }

    @Test
    fun `the transaction log is read over the window and its rows come back oldest first`() {
        venue.answers["private/get_transaction_log"] = "transaction-log-trades.json"

        val rows = client.transactions("USDC", 1_791_131_904_000L, 1_791_132_100_000L)

        val sent = venue.next("private/get_transaction_log").params()
        assertThat(sent.text("start_timestamp")).isEqualTo("1791131904000")
        assertThat(sent.text("end_timestamp")).isEqualTo("1791132100000")
        assertThat(sent).doesNotContainKey("continuation")
        assertThat(rows.map { it.id }).containsExactly(149271162L, 149271163L, 149271746L, 149271747L, 149271748L)
    }

    @Test
    fun `the settlement history is read back from the window's end and its rows come back oldest first`() {
        venue.answers["private/get_settlement_history_by_currency"] = "settlement-history.json"

        val rows = client.settlements("USDC", 1_790_928_001_112L, 1_791_014_400_571L)

        val sent = venue.next("private/get_settlement_history_by_currency").params()
        assertThat(sent.text("search_start_timestamp")).isEqualTo("1791014400571")
        assertThat(sent.text("currency")).isEqualTo("USDC")
        assertThat(rows).hasSize(4)
        assertThat(rows.map { it.timestampMs }).isSorted
        assertThat(rows.first().timestampMs).isEqualTo(1_790_928_001_112L)
    }
}
