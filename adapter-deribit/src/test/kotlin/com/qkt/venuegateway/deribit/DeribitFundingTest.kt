package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.deribit.client.DeribitFundingRate
import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import java.math.BigDecimal
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** What the Deribit adapter declares and reports of funding, from rows recorded on testnet. */
class DeribitFundingTest {
    private fun recorded(name: String) =
        Json
            .parseToJsonElement(javaClass.getResource("/fixtures/private/$name")!!.readText())
            .jsonObject["response"]!!
            .jsonObject["result"]!!
            .jsonObject

    private val open =
        DeribitPrivateJson.order(recorded("buy-limit-open.json")["order"]!!.jsonObject)
    private val deribit = ScriptedDeribit(open)

    @Test
    fun `it declares every capability, the tape, liquidations and depth included`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = deribit.adapter(dir, unusedMarket())

        assertThat(adapter.capabilities).containsExactlyInAnyOrder(
            Capability.BARS,
            Capability.QUOTES,
            Capability.SETTLEMENTS,
            Capability.FUNDING,
            Capability.FUNDING_RATES,
            Capability.MARK_PRICES,
            Capability.OPEN_INTEREST,
            Capability.OPTION_MARKS,
            Capability.TRADES,
            Capability.LIQUIDATIONS,
            Capability.DEPTH,
        )
        adapter.close()
    }

    @Test
    fun `log rows that realized no funding are no funding records`(
        @TempDir dir: Path,
    ) {
        deribit.transactions += DeribitPrivateJson.transactionPage(recorded("transaction-log-trades.json")).first
        val (adapter, _) = deribit.adapter(dir, unusedMarket())

        assertThat(deribit.transactions).hasSize(5).allMatch { it.type == "trade" && it.interestPl!!.signum() == 0 }
        assertThat(adapter.funding(0, Long.MAX_VALUE)).isEmpty()
        adapter.close()
    }

    @Test
    fun `funding realized at a fill and at the settlement is reported, settlement rows with position`(
        @TempDir dir: Path,
    ) {
        // Recorded 2026-10-05: a 20 SOL long opened, held through the 08:00 UTC settlement and closed.
        deribit.transactions += DeribitPrivateJson.transactionPage(recorded("transaction-log-funding.json")).first
        val (adapter, _) = deribit.adapter(dir, solPerpetualMarket())

        val funding = adapter.funding(0, Long.MAX_VALUE).associateBy { it.fundingId }

        assertThat(funding.keys).containsExactlyInAnyOrder("tx-149592450", "tx-149603303", "tx-149687084")
        val settlement = funding.getValue("tx-149603303")
        assertThat(settlement.amount).isEqualByComparingTo("-0.02127416")
        assertThat(settlement.position).isEqualByComparingTo("20")
        assertThat(settlement.symbol).isEqualTo("SOL_USDC-PERPETUAL")
        assertThat(funding.getValue("tx-149687084").amount).isEqualByComparingTo("-0.10591043")
        assertThat(funding.getValue("tx-149687084").position).isNull()
        assertThat(funding.getValue("tx-149592450").amount).isEqualByComparingTo("0.11082282")
        adapter.close()
    }

    @Test
    fun `the transaction log reads back each row's id, type, instrument, funding and position`() {
        val (rows, next) = DeribitPrivateJson.transactionPage(recorded("transaction-log-trades.json"))

        assertThat(next).isNull()
        val reduce = rows.first()
        assertThat(reduce.id).isEqualTo(149_271_748L)
        assertThat(reduce.instrument).isEqualTo("SOL_USDC-PERPETUAL")
        assertThat(reduce.currency).isEqualTo("USDC")
        assertThat(reduce.position).isEqualByComparingTo("150")
        assertThat(reduce.timestampMs).isEqualTo(1_791_132_060_128L)
    }

    @Test
    fun `funding rates are each hour's rate at the index, the price a unit long paid on`() {
        val rate =
            DeribitMarketMapping.fundingRate(
                DeribitFundingRate(1_791_126_000_000L, BigDecimal("0.00004182650335544141"), BigDecimal("121.8285")),
            )

        assertThat(rate.timeMs).isEqualTo(1_791_126_000_000L)
        assertThat(rate.rate.toPlainString()).isEqualTo("0.00004182650335544141")
        assertThat(rate.price).isEqualByComparingTo("121.8285")
    }

    /** Lists nothing; answers SOL_USDC-PERPETUAL's recorded instrument when looked up by name. */
    private fun solPerpetualMarket(): DeribitMarketData {
        val text = javaClass.getResource("/fixtures/instrument-SOL_USDC-PERPETUAL.json")!!.readText()
        val sol = DeribitJson.instrument(Json.parseToJsonElement(text).jsonObject["result"]!!.jsonObject)
        return java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(DeribitMarketData::class.java),
        ) { _, method, args ->
            when (method.name) {
                "instruments" -> emptyList<Any>()
                "instrument" -> sol.also { check(args[0] == sol.name) }
                else -> error("market ${method.name} is not used here")
            }
        } as DeribitMarketData
    }
}
