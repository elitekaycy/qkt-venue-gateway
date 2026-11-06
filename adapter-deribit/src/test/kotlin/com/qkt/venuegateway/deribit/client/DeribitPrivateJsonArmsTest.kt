package com.qkt.venuegateway.deribit.client

import com.qkt.venuegateway.deribit.client.DeribitEdits.num
import com.qkt.venuegateway.deribit.client.DeribitEdits.text
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeribitPrivateJsonArmsTest {
    private val order = recordedResult("private/buy-limit-open.json").jsonObject["order"]!!.jsonObject
    private val trade =
        recordedObject("private/user-trades-by-time.json")["trades"]
            .let {
                (it as JsonArray).first()
            }.jsonObject

    @Test
    fun `an empty label is no label, and a missing one is none too`() {
        assertThat(DeribitPrivateJson.order(order.set("label" to text(""))).label).isNull()
        assertThat(DeribitPrivateJson.order(order.without("label")).label).isNull()
        assertThat(DeribitPrivateJson.order(order.set("label" to text("kit-1"))).label).isEqualTo("kit-1")
        assertThat(DeribitPrivateJson.trade(trade.set("label" to text(""))).label).isNull()
        assertThat(DeribitPrivateJson.trade(trade.without("label")).label).isNull()
        assertThat(DeribitPrivateJson.trade(trade.set("label" to text("kit-2"))).label).isEqualTo("kit-2")
    }

    @Test
    fun `an order with no filled amount, trigger flag, reduce flag or cancel reason reads them as none`() {
        val bare = DeribitPrivateJson.order(order.without("filled_amount", "triggered", "reduce_only", "cancel_reason"))
        val flagged =
            DeribitPrivateJson.order(
                order.set(
                    "triggered" to JsonPrimitive(true),
                    "reduce_only" to JsonPrimitive(true),
                    "cancel_reason" to text("x"),
                ),
            )

        assertThat(bare.filledAmount).isEqualTo(BigDecimal.ZERO)
        assertThat(bare.triggered).isNull()
        assertThat(bare.reduceOnly).isFalse
        assertThat(bare.cancelReason).isNull()
        assertThat(flagged.triggered).isTrue
        assertThat(flagged.reduceOnly).isTrue
        assertThat(flagged.cancelReason).isEqualTo("x")
    }

    @Test
    fun `a price that is a word, such as market_price, or is missing is no price, and a number is exact`() {
        assertThat(DeribitPrivateJson.order(order.set("price" to text("market_price"))).price).isNull()
        assertThat(DeribitPrivateJson.order(order.without("price")).price).isNull()
        assertThat(DeribitPrivateJson.order(order.set("price" to num("101.50"))).price).isEqualTo(BigDecimal("101.50"))
    }

    @Test
    fun `an order missing a required field is refused naming it`() {
        listOf(
            "order_id",
            "instrument_name",
            "direction",
            "order_type",
            "order_state",
            "time_in_force",
            "amount",
        ).forEach {
            assertThatThrownBy { DeribitPrivateJson.order(order.without(it)) }.describedAs(it).hasMessageContaining(it)
        }
        assertThatThrownBy { DeribitPrivateJson.order(order.without("creation_timestamp")) }
            .hasMessageContaining("creation_timestamp")
    }

    @Test
    fun `a trade page says whether more follow, none meaning no`() {
        val page = recordedObject("private/user-trades-by-time.json")

        assertThat(DeribitPrivateJson.tradePage(page.without("has_more")).more).isFalse
        assertThat(DeribitPrivateJson.tradePage(page.set("has_more" to JsonPrimitive(true))).more).isTrue
    }

    @Test
    fun `a trade, position and account missing a required field is refused naming it`() {
        val position = recordedObject("private/positions-future-linear.json")
        val account = recordedObject("private/account-summary.json")

        assertThatThrownBy {
            DeribitPrivateJson.trade(
                trade.without("fee_currency"),
            )
        }.hasMessageContaining("fee_currency")
        assertThatThrownBy { DeribitPrivateJson.position(position.without("average_price")) }
            .hasMessageContaining("average_price")
        assertThatThrownBy { DeribitPrivateJson.account(account.without("available_funds")) }
            .hasMessageContaining("available_funds")
        assertThat(DeribitPrivateJson.position(position.without("size_currency")).sizeCurrency).isNull()
    }

    @Test
    fun `a transaction log page continues on a numeric continuation only`() {
        val log = recordedObject("private/transaction-log-funding.json")

        assertThat(DeribitPrivateJson.transactionPage(log).second).isNull()
        assertThat(DeribitPrivateJson.transactionPage(log.set("continuation" to num("149687084"))).second)
            .isEqualTo(149_687_084L)
        assertThat(DeribitPrivateJson.transactionPage(log.set("continuation" to text("abc"))).second).isNull()
        assertThat(DeribitPrivateJson.transactionPage(log.without("continuation")).second).isNull()
    }

    @Test
    fun `a settlement page continues on a token, and none or no token ends it`() {
        val page = recordedObject("private/settlement-history-page-1.json")

        assertThat(DeribitPrivateJson.settlementPage(page).second).isNotNull
        assertThat(DeribitPrivateJson.settlementPage(page.set("continuation" to text("none"))).second).isNull()
        assertThat(DeribitPrivateJson.settlementPage(page.without("continuation")).second).isNull()
        val rows = (page["settlements"] as JsonArray).first().jsonObject
        assertThatThrownBy {
            DeribitPrivateJson.settlementPage(
                JsonObject(
                    page + ("settlements" to JsonArray(listOf(rows.without("type")))),
                ),
            )
        }.hasMessageContaining("type")
    }
}
