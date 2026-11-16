package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import com.qkt.venuegateway.deribit.market.DeribitTape
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The mapping's remaining rules over the testnet recordings: stops to send, delivery fees, values it does not know. */
class DeribitMappingEdgesTest {
    private fun json(path: String) =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/$path")!!.readText()).jsonObject

    private fun result(name: String): JsonElement = json("private/$name")["response"]!!.jsonObject["result"]!!

    private val accountRows = DeribitPrivateJson.settlementPage(result("settlement-history.json").jsonObject).first
    private val expiryLog = DeribitPrivateJson.transactionPage(result("transaction-log-expiry.json").jsonObject).first
    private val trxCall = "TRX_USDC-2OCT26-0d342-C"

    @Test
    fun `a stop to send is a stop market on the trigger, carrying its stop price and no limit price`() {
        val stop =
            DeribitMapping.newOrder(
                NewOrder(
                    "c-2",
                    "BTC_USDC-PERPETUAL",
                    Side.BUY,
                    OrderType.STOP,
                    BigDecimal("0.0001"),
                    BigDecimal("126278.8"),
                    BigDecimal("126268.8"),
                    TimeInForce.GTC,
                ),
                "index_price",
            )

        assertThat(stop.type).isEqualTo("stop_market")
        assertThat(stop.direction).isEqualTo("buy")
        assertThat(stop.triggerPrice).isEqualByComparingTo("126268.8")
        assertThat(stop.trigger).isEqualTo("index_price")
        assertThat(stop.price).isNull()
        assertThat(stop.timeInForce).isEqualTo("good_til_cancelled")
    }

    @Test
    fun `a position kind or a direction Deribit adds later fails by name instead of being guessed`() {
        val future = DeribitPrivateJson.position(result("positions-future-linear.json").jsonArray.single().jsonObject)
        val open = DeribitPrivateJson.order(result("buy-limit-open.json").jsonObject["order"]!!.jsonObject)

        assertThatThrownBy { DeribitMapping.position(future.copy(kind = "spot")) }.hasMessageContaining("kind spot")
        assertThatThrownBy { DeribitMapping.order(open.copy(direction = "zero")) }
            .hasMessageContaining("direction zero")
    }

    @Test
    fun `an expiry Deribit charged a fee on reports it as a delivery fee in the log row's currency`() {
        val charged =
            expiryLog.map { if (it.type == "expiry") it.copy(commission = BigDecimal("0.0003")) else it }
        val exercise = accountRows.filter { it.type == "exercise" }

        val settled = DeribitSettlementMapping.settlements(exercise, ::expired) { charged }.single()

        assertThat(settled.symbol).isEqualTo(trxCall)
        val fee = settled.costs.single()
        assertThat(fee.kind).isEqualTo(CostKind.DELIVERY_FEE)
        assertThat(fee.amount).isEqualByComparingTo("0.0003")
        assertThat(fee.currency).isEqualTo("USDC")
    }

    @Test
    fun `an option whose type is neither call nor put is refused by name rather than priced`() {
        val odd = expired("SOL_USDC-4OCT26-114-C").copy(optionType = "binary")

        assertThatThrownBy { DeribitSettlementMapping.unitPrice(odd, BigDecimal("120")) }
            .hasMessageContaining("option_type binary")
    }

    @Test
    fun `a print whose direction is neither buy nor sell is refused by name`() {
        val result = json("tape-liquidation-taker.json")["result"]!!.jsonObject
        val print = DeribitJson.publicTrades(result).first { it.liquidation == "T" }

        assertThatThrownBy { DeribitTape.liquidatedSides(print.copy(direction = "zero")) }
            .hasMessageContaining("direction zero")
    }

    private fun expired(name: String) =
        DeribitJson.instrument(json("instrument-expired-$name.json")["result"]!!.jsonObject)
}
