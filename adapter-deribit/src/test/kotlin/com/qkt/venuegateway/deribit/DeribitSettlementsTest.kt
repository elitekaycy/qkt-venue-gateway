package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import com.qkt.venuegateway.deribit.client.DeribitSettlement
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Deribit's expiries as settlements, from testnet recordings: the account's TRX call that expired out of the
 * money on 2026-10-02, and the public settlement rows of the 2026-10-04 expiry (the same shape).
 */
class DeribitSettlementsTest {
    private fun json(path: String) =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/$path")!!.readText()).jsonObject

    private fun recorded(name: String): JsonObject =
        json("private/$name")["response"]!!.jsonObject["result"]!!.jsonObject

    private fun instrument(name: String): DeribitInstrument =
        DeribitJson.instrument(json("instrument-expired-$name.json")["result"]!!.jsonObject)

    private val accountRows = DeribitPrivateJson.settlementPage(recorded("settlement-history.json")).first
    private val expiryLog = DeribitPrivateJson.transactionPage(recorded("transaction-log-expiry.json")).first
    private val trxCall = "TRX_USDC-2OCT26-0d342-C"
    private val deribit =
        ScriptedDeribit(DeribitPrivateJson.order(recorded("buy-limit-open.json")["order"]!!.jsonObject))

    @Test
    fun `the account's expired out-of-the-money call settles at zero at the exercise instant, with no fee charged`(
        @TempDir dir: Path,
    ) {
        deribit.settled += accountRows
        deribit.transactions += expiryLog
        val (adapter, _) = deribit.adapter(dir, expiredMarket())

        val settled = adapter.settlements(0, Long.MAX_VALUE)

        assertThat(settled).hasSize(1)
        assertThat(settled[0].symbol).isEqualTo(trxCall)
        assertThat(settled[0].price).isEqualByComparingTo("0")
        assertThat(settled[0].timeMs).isEqualTo(1_790_928_001_112L)
        assertThat(settled[0].costs).isEmpty()
        adapter.close()
    }

    @Test
    fun `the exercise row carries the delivery price the option settled against, and its expiry row the fee`() {
        val exercise = accountRows.single { it.type == "exercise" }
        val expiry = expiryLog.single { it.type == "expiry" }

        assertThat(exercise.indexPrice.toPlainString()).isEqualTo("0.334323")
        assertThat(expiry.instrument).isEqualTo(trxCall)
        assertThat(expiry.timestampMs).isEqualTo(exercise.timestampMs)
        assertThat(expiry.commission).isEqualByComparingTo("0")
    }

    @Test
    fun `daily session settlements are not expiries, and the transaction log is then not read`(
        @TempDir dir: Path,
    ) {
        deribit.settled += DeribitPrivateJson.settlementPage(recorded("settlement-history-type-settlement.json")).first
        val (adapter, _) = deribit.adapter(dir, unusedMarket())

        assertThat(deribit.settled).hasSize(3).allMatch { it.type == "settlement" }
        assertThat(adapter.settlements(0, Long.MAX_VALUE)).isEmpty()
        assertThat(deribit.logReads).isZero()
        adapter.close()
    }

    @Test
    fun `a future delivers at the delivery price, an in-the-money call and put at their intrinsic value`() {
        val names = listOf("BTC_USDC-4OCT26", "SOL_USDC-4OCT26-114-C", "BTC_USDC-4OCT26-86000-P")
        val rows = DeribitPrivateJson.settlementPage(json("last-settlements-usdc.json")["result"]!!.jsonObject).first

        val settled =
            DeribitSettlementMapping
                .settlements(rows.filter { it.instrument in names }, ::instrument) { emptyList() }
                .associateBy { it.symbol }

        assertThat(settled.getValue("BTC_USDC-4OCT26").price.toPlainString()).isEqualTo("84980.71")
        assertThat(settled.getValue("SOL_USDC-4OCT26-114-C").price.toPlainString()).isEqualTo("7.003")
        assertThat(settled.getValue("BTC_USDC-4OCT26-86000-P").price.toPlainString()).isEqualTo("1019.29")
        assertThat(settled.values.map { it.timeMs }).containsOnly(1_791_100_801_122L)
    }

    @Test
    fun `an exercise row's mark price is zero even in the money, so it is never the settlement price`() {
        val row =
            json("last-settlements-usdc.json")["result"]!!
                .jsonObject["settlements"]!!
                .jsonArray
                .map { it.jsonObject }
                .single { it["instrument_name"]!!.jsonPrimitive.content == "SOL_USDC-4OCT26-114-C" }

        assertThat(row["type"]!!.jsonPrimitive.content).isEqualTo("exercise")
        assertThat(row["mark_price"]!!.jsonPrimitive.content).isEqualTo("0.0")
    }

    @Test
    fun `a settlement type Deribit has not been seen to send is refused by name`() {
        val odd = DeribitSettlement("liquidation", trxCall, accountRows.first().indexPrice, 1L)

        assertThatThrownBy { DeribitSettlementMapping.settlements(listOf(odd), ::instrument) { emptyList() } }
            .hasMessageContaining("liquidation")
    }

    /** Lists nothing live; answers the recorded expired instruments one by one. */
    private fun expiredMarket(): DeribitMarketData =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(DeribitMarketData::class.java),
        ) { _, method, args ->
            when (method.name) {
                "instruments" -> emptyList<DeribitInstrument>()
                "instrument" -> instrument(args[0] as String)
                else -> error("market ${method.name} is not used here")
            }
        } as DeribitMarketData
}
