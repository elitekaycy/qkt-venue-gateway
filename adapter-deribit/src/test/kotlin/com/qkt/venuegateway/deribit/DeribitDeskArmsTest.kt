package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.deribit.client.DeribitException
import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitOrder
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import com.qkt.venuegateway.deribit.client.recordedResult
import java.math.BigDecimal
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DeribitDeskArmsTest {
    @TempDir lateinit var dir: Path
    private val open: DeribitOrder =
        DeribitPrivateJson.order(recordedResult("private/buy-limit-open.json").jsonObject["order"]!!.jsonObject)
    private val deribit = ScriptedDeribit(open)
    private val listed =
        (recordedResult("instruments-future.json") as JsonArray).map { DeribitJson.instrument(it.jsonObject) }
    private val market =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(DeribitMarketData::class.java),
        ) { _, m, a ->
            if (m.name == "instruments" && a[1] == "future") listed else emptyList<Any>()
        } as DeribitMarketData

    private fun sell(label: String = open.label!!) =
        NewOrder(
            label,
            "BTC_USDC-PERPETUAL",
            Side.SELL,
            OrderType.MARKET,
            BigDecimal("0.0001"),
            timeInForce = TimeInForce.GTC,
        )

    @Test
    fun `an order deribit accepts but answers without its label is refused by name`() {
        deribit.placed = open.copy(label = null)
        val (adapter, _) = deribit.adapter(dir, market)

        assertThatThrownBy { adapter.place(sell()) }.hasMessageContaining("without its label")
    }

    @Test
    fun `a change naming no quantity keeps the order's own, and an order unknown by label changes nothing`() {
        deribit.byLabel[open.label!!] = listOf(open)
        val (adapter, _) = deribit.adapter(dir, market)

        val changed = adapter.modify(open.label!!, OrderChange(limitPrice = BigDecimal("101")))
        val unknown = adapter.modify("nobody", OrderChange(quantity = BigDecimal.ONE))

        assertThat(changed).isTrue
        assertThat(deribit.edits.single()).contains(open.amount.toPlainString()).contains("101")
        assertThat(unknown).isFalse
        assertThat(deribit.edits).hasSize(1)
    }

    @Test
    fun `the latest of an order's records by label is the one reported`() {
        deribit.byLabel[open.label!!] = listOf(open.copy(updatedMs = 5, state = "cancelled"), open.copy(updatedMs = 9))
        val (adapter, _) = deribit.adapter(dir, market)

        assertThat(adapter.orderByLabel(open.label!!)!!.updatedAtMs).isEqualTo(9L)
        assertThat(adapter.orderByLabel("nobody")).isNull()
    }

    @Test
    fun `a cancel deribit refuses for a remainder it cannot read back as ended is raised, one it can is settled`() {
        val remainder = open.copy(originalOrderType = "market", orderType = "limit", price = null)
        val refusal = DeribitException(11044, "not_open_order")
        deribit.cancels[remainder.orderId] = { throw refusal }
        val remainders = DeribitMarketRemainders(deribit, "USDC")

        deribit.byLabel[remainder.label!!] = emptyList()
        assertThatThrownBy { remainders.settled(remainder) }.isSameAs(refusal)

        deribit.byLabel[remainder.label!!] = listOf(remainder)
        assertThatThrownBy { remainders.settled(remainder) }.isSameAs(refusal)

        val ended = remainder.copy(state = "filled", originalOrderType = null, orderType = "limit")
        deribit.byLabel[remainder.label!!] = listOf(ended)
        assertThat(remainders.settled(remainder)).isEqualTo(ended)
    }

    @Test
    fun `a remainder without a price is cancelled soon and not reported`() {
        val remainder = open.copy(originalOrderType = "market", orderType = "limit", price = null)

        assertThat(DeribitMarketRemainders(deribit, "USDC").reportable(remainder)).isFalse
        assertThat(deribit.cancelsSent).containsExactly(remainder.orderId)
        assertThat(DeribitMarketRemainders(deribit, "USDC").reportable(open)).isTrue
    }
}
