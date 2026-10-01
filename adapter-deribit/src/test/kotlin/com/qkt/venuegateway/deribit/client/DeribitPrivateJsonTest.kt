package com.qkt.venuegateway.deribit.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Testnet responses recorded 2026-10-01 read back exactly as Deribit reported them. */
class DeribitPrivateJsonTest {
    private fun fixture(name: String): JsonElement =
        Json
            .parseToJsonElement(javaClass.getResource("/fixtures/private/$name")!!.readText())
            .jsonObject["response"]!!
            .jsonObject["result"]!!

    @Test
    fun `a resting limit reads back open, unfilled, with its label, price and timestamps`() {
        val order = DeribitPrivateJson.order(fixture("buy-limit-open.json").jsonObject["order"]!!.jsonObject)

        assertThat(order.label).isEqualTo("kit-probe-1790866831")
        assertThat(order.state).isEqualTo("open")
        assertThat(order.direction).isEqualTo("buy")
        assertThat(order.orderType).isEqualTo("limit")
        assertThat(order.amount).isEqualByComparingTo("0.0001")
        assertThat(order.filledAmount).isZero
        assertThat(order.price).isEqualByComparingTo("50461.9")
        assertThat(order.timeInForce).isEqualTo("good_til_cancelled")
        assertThat(order.createdMs).isEqualTo(1_790_866_831_549L)
    }

    @Test
    fun `an untriggered stop has no filled amount and a market price word, read as zero and no price`() {
        val order =
            DeribitPrivateJson.order(
                fixture("buy-stop-market-untriggered.json").jsonObject["order"]!!.jsonObject,
            )

        assertThat(order.state).isEqualTo("untriggered")
        assertThat(order.filledAmount).isZero
        assertThat(order.price).isNull()
        assertThat(order.triggerPrice).isEqualByComparingTo("126268.8")
        assertThat(order.triggered).isFalse
    }

    @Test
    fun `a triggered stop is a new market order that keeps its label and trigger price`() {
        val orders =
            fixture("order-state-by-label-stop-triggered.json").jsonArray.map {
                DeribitPrivateJson.order(it.jsonObject)
            }
        val fired = orders.single { it.triggered == true }

        assertThat(fired.orderType).isEqualTo("market")
        assertThat(fired.state).isEqualTo("filled")
        assertThat(fired.filledAmount).isEqualByComparingTo("0.0001")
        assertThat(fired.averagePrice).isEqualByComparingTo("84085.9")
        assertThat(fired.triggerPrice).isEqualByComparingTo("84096.0")
        assertThat(orders.map { it.label }.distinct()).containsExactly("kit-trig3-1790867232")
    }

    @Test
    fun `a cancelled order carries its cancel reason`() {
        val order =
            DeribitPrivateJson.order(
                fixture("order-state-by-label-cancelled.json").jsonArray.single().jsonObject,
            )

        assertThat(order.state).isEqualTo("cancelled")
        assertThat(order.cancelReason).isEqualTo("user_request")
    }

    @Test
    fun `trades carry the venue trade id, label, fee and fee currency, and a page says whether more follow`() {
        val page = DeribitPrivateJson.tradePage(fixture("user-trades-by-time.json").jsonObject)

        assertThat(page.more).isFalse
        assertThat(page.items.map { it.tradeId }).containsExactly("USDC-55455987", "USDC-55455988")
        val first = page.items.first()
        assertThat(first.label).isEqualTo("kit-fill-1790866869-o")
        assertThat(first.price).isEqualByComparingTo("84058.7")
        assertThat(first.fee).isEqualByComparingTo("0.00420293")
        assertThat(first.feeCurrency).isEqualTo("USDC")
        assertThat(first.timestampMs).isEqualTo(1_790_866_869_705L)
    }

    @Test
    fun `positions keep both size fields as reported - dollars and coin for linear futures, coin alone for options`() {
        val future = DeribitPrivateJson.position(fixture("positions-future-linear.json").jsonArray.single().jsonObject)
        val option = DeribitPrivateJson.position(fixture("positions-option.json").jsonArray.single().jsonObject)
        val flat = DeribitPrivateJson.position(fixture("positions-future-flat.json").jsonArray.single().jsonObject)

        assertThat(future.kind).isEqualTo("future")
        assertThat(future.size).isEqualByComparingTo("8.406277")
        assertThat(future.sizeCurrency).isEqualByComparingTo("0.0001")
        assertThat(option.size).isEqualByComparingTo("10000")
        assertThat(option.sizeCurrency).isNull()
        assertThat(flat.size).isZero
    }

    @Test
    fun `the account summary reads its money`() {
        val account = DeribitPrivateJson.account(fixture("account-summary.json").jsonObject)

        assertThat(account.currency).isEqualTo("USDC")
        assertThat(account.equity).isEqualByComparingTo("99999.19322085")
        assertThat(account.availableFunds).isEqualByComparingTo("99998.26547786")
    }
}
