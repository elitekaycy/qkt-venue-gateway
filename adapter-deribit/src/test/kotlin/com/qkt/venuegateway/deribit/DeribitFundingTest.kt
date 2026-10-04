package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.VenueUnsupportedException
import com.qkt.venuegateway.deribit.client.DeribitFundingRate
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import java.math.BigDecimal
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    fun `it declares bars, quotes, funding and funding rates, and refuses settlements as unsupported`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = deribit.adapter(dir, unusedMarket())

        assertThat(adapter.capabilities)
            .containsExactlyInAnyOrder(Capability.BARS, Capability.QUOTES, Capability.FUNDING, Capability.FUNDING_RATES)
        assertThatThrownBy { adapter.settlements(0, 1) }.isInstanceOf(VenueUnsupportedException::class.java)
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
}
