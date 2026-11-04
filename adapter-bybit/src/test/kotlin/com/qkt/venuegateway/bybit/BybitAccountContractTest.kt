package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.bybit.client.BybitRest
import com.qkt.venuegateway.support.JsonReads.needDecimal
import com.qkt.venuegateway.testkit.RecordingListener
import java.math.BigDecimal
import java.math.MathContext
import java.nio.file.Path
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The account the Bybit adapter states, measured against the testnet while it holds a BTCUSDT position at its smallest
 * size. Runs only with `BYBIT_CLIENT_ID` and `BYBIT_CLIENT_SECRET` set to a testnet API key, as [BybitContractTest].
 */
class BybitAccountContractTest {
    private val key = System.getenv("BYBIT_CLIENT_ID")
    private val secret = System.getenv("BYBIT_CLIENT_SECRET")
    private val perp = "BTCUSDT"
    private val size = BigDecimal("0.001")

    @Test
    fun `holding a position, margin used over equity is the initial margin rate bybit states for the account`(
        @TempDir dir: Path,
    ) {
        assumeTrue(!key.isNullOrBlank() && !secret.isNullOrBlank(), "no testnet key")
        val context =
            AdapterContext(
                mapOf("environment" to "testnet", "category" to "linear"),
                System::currentTimeMillis,
                dir,
                Credentials(key, secret),
            )
        val adapter = BybitAdapterFactory().create(context)
        val listener = RecordingListener()
        adapter.connect(listener)
        val run = System.currentTimeMillis().toString(36)
        try {
            listener.await("the venue link to come up") { listener.connections.contains(true) }
            adapter.place(order("acct-$run-open", Side.BUY, reduceOnly = false))
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (adapter.account().marginUsed.signum() == 0) {
                check(System.currentTimeMillis() < deadline) { "no margin in use after buying $size $perp" }
                Thread.sleep(POLL_MS)
            }
            val account = adapter.account()
            val stated = imRate()

            assertThat(account.currency).isEqualTo("USD")
            assertThat(account.marginUsed.divide(account.equity, MathContext.DECIMAL64))
                .isCloseTo(stated, within(BigDecimal("0.0001")))
        } finally {
            runCatching { adapter.place(order("acct-$run-close", Side.SELL, reduceOnly = true)) }
            adapter.close()
        }
    }

    private fun order(
        id: String,
        side: Side,
        reduceOnly: Boolean,
    ) = NewOrder(id, perp, side, OrderType.MARKET, size, null, null, TimeInForce.GTC, reduceOnly = reduceOnly)

    /** Bybit's own `accountIMRate`: the unified account's initial margin over its margin balance. */
    private fun imRate(): BigDecimal {
        val rest = BybitRest(BybitEnvironment.TESTNET.restUrl, key, secret)
        val wallet = rest.get("/v5/account/wallet-balance", listOf("accountType" to "UNIFIED"), signed = true)
        return wallet.result["list"]!!
            .jsonArray
            .first()
            .jsonObject
            .needDecimal("accountIMRate")
    }

    private companion object {
        const val WAIT_MS = 30_000L
        const val POLL_MS = 500L
    }
}
