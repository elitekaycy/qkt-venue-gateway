package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import com.qkt.venuegateway.deribit.client.DeribitSettlement
import com.qkt.venuegateway.deribit.client.DeribitTransaction
import com.qkt.venuegateway.deribit.client.recordedObject
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeribitSettlementArmsTest {
    private val rows: List<DeribitSettlement> =
        DeribitPrivateJson.settlementPage(recordedObject("private/settlement-history.json")).first
    private val exercise = rows.first { it.type == "exercise" }
    private val log: List<DeribitTransaction> =
        DeribitPrivateJson.transactionPage(recordedObject("private/transaction-log-expiry.json")).first
    private val expiry = log.first { it.type == "expiry" }
    private val call: DeribitInstrument =
        DeribitJson.instrument(
            recordedObject("instrument-expired-TRX_USDC-2OCT26-0d342-C.json"),
        )
    private val ten = BigDecimal("10")

    private fun settle(
        rows: List<DeribitSettlement>,
        fees: List<DeribitTransaction> = log,
    ) = DeribitSettlementMapping.settlements(rows, { call }) { fees }

    @Test
    fun `a settlement type deribit adds later is refused naming it and its instrument`() {
        assertThatThrownBy { settle(listOf(exercise.copy(type = "liquidation"))) }
            .hasMessageContaining("liquidation")
            .hasMessageContaining(exercise.instrument)
    }

    @Test
    fun `rows that are no expiry settle nothing and do not read the account log`() {
        val none =
            DeribitSettlementMapping.settlements(listOf(exercise.copy(type = "settlement")), { call }) {
                error("the log must not be read")
            }

        assertThat(none).isEmpty()
        assertThat(DeribitSettlementMapping.settlements(emptyList(), { call }) { error("not read") }).isEmpty()
    }

    @Test
    fun `an expiry fee is matched on its instrument and its instant, a zero fee being no cost`() {
        val matching = expiry.copy(instrument = exercise.instrument, timestampMs = exercise.timestampMs)

        val charged = settle(listOf(exercise), listOf(matching.copy(commission = BigDecimal("0.01")))).single()
        val free = settle(listOf(exercise), listOf(matching.copy(commission = BigDecimal.ZERO))).single()
        val unrecorded = settle(listOf(exercise), listOf(matching.copy(commission = null))).single()
        val otherInstrument =
            settle(
                listOf(exercise),
                listOf(matching.copy(instrument = "BTC_USDC-30OCT26", commission = ten)),
            ).single()
        val otherInstant = settle(listOf(exercise), listOf(matching.copy(timestampMs = 5L, commission = ten))).single()
        val otherType = settle(listOf(exercise), listOf(matching.copy(type = "trade", commission = ten))).single()

        assertThat(charged.costs.single().kind).isEqualTo(CostKind.DELIVERY_FEE)
        assertThat(charged.costs.single().amount).isEqualByComparingTo("0.01")
        listOf(free, unrecorded, otherInstrument, otherInstant, otherType).forEach { assertThat(it.costs).isEmpty() }
    }

    @Test
    fun `an option's settlement price is what it is worth at delivery, never below zero`() {
        val strike = call.strike!!

        assertThat(
            DeribitSettlementMapping.unitPrice(call.copy(optionType = "call"), strike + ten),
        ).isEqualByComparingTo("10")
        assertThat(
            DeribitSettlementMapping.unitPrice(call.copy(optionType = "call"), strike - ten),
        ).isEqualByComparingTo("0")
        assertThat(
            DeribitSettlementMapping.unitPrice(call.copy(optionType = "put"), strike - ten),
        ).isEqualByComparingTo("10")
        assertThat(
            DeribitSettlementMapping.unitPrice(call.copy(optionType = "put"), strike + ten),
        ).isEqualByComparingTo("0")
        assertThat(DeribitSettlementMapping.unitPrice(call.copy(kind = "future", strike = null), ten)).isEqualTo(ten)
    }

    @Test
    fun `an option with no strike is refused by name rather than priced`() {
        assertThatThrownBy { DeribitSettlementMapping.unitPrice(call.copy(strike = null), ten) }
            .hasMessageContaining("no strike")
            .hasMessageContaining(call.name)
    }
}
