package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.deribit.client.DeribitFundingRate
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitMarkTrade
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitPage
import com.qkt.venuegateway.deribit.client.DeribitPublicTrade
import com.qkt.venuegateway.deribit.client.DeribitTicker
import com.qkt.venuegateway.deribit.client.DeribitTickers
import com.qkt.venuegateway.testkit.AdapterContractTest
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate

/** The paper adapter keeps the adapter contract, over a market that quotes one perpetual and an unquotable option. */
class PaperContractTest : AdapterContractTest() {
    private val perp = "BTC_USDC-PERPETUAL"
    private val put = "BTC_USDC-30DEC50-82000-P"
    private val minute = 60_000L

    private val market =
        object : DeribitMarketData {
            override fun instruments(
                currency: String,
                kind: String,
            ) = if (kind == "future") listOf(listed(perp, null, null)) else listOf(listed(put, EXPIRY_MS, "82000"))

            override fun instrument(name: String) =
                (instruments("USDC", "future") + instruments("USDC", "option")).first {
                    it.name ==
                        name
                }

            override fun ticker(name: String) = quoted(name)

            override fun klines(
                name: String,
                minutes: Long,
                fromMs: Long,
                toMs: Long,
            ): List<DeribitKline> {
                val window = minutes * minute
                val first = (fromMs + window - 1) / window * window
                return (first until minOf(toMs, System.currentTimeMillis() - window + 1) step window).map {
                    DeribitKline(
                        it,
                        BigDecimal("84000"),
                        BigDecimal("84010"),
                        BigDecimal("83990"),
                        BigDecimal("84005"),
                        BigDecimal.ONE,
                    )
                }
            }

            override fun fundingRates(
                name: String,
                fromMs: Long,
                toMs: Long,
            ) = ((fromMs + HOUR_MS - 1) / HOUR_MS * HOUR_MS..toMs step HOUR_MS).map {
                DeribitFundingRate(it, BigDecimal("0.00001"), BigDecimal("84000"))
            }

            /** One trade a minute, half a minute in, numbered by its minute. */
            override fun edgeTrade(
                name: String,
                fromMs: Long,
                toMs: Long,
                newest: Boolean,
            ): DeribitMarkTrade? {
                val minutes = ((fromMs - minute / 2 + minute - 1) / minute..(toMs - minute / 2) / minute)
                return (if (newest) minutes.lastOrNull() else minutes.firstOrNull())?.let(::traded)
            }

            override fun tradesFrom(
                name: String,
                fromMs: Long,
                toMs: Long,
                count: Int,
            ) = DeribitPage(
                ((fromMs - minute / 2 + minute - 1) / minute..(toMs - minute / 2) / minute).map(::traded),
                false,
            )

            /** The same trades as prints: the aggressor alternating, every tenth minute's liquidating its taker. */
            override fun tape(
                name: String,
                fromMs: Long,
                toMs: Long,
                count: Int,
            ) = DeribitPage(tradesFrom(name, fromMs, toMs, count).items.map(::printed).take(count), false)

            override fun deliveryPrices(
                index: String,
                count: Int,
            ) = emptyList<Pair<LocalDate, BigDecimal>>()
        }

    private fun traded(minuteNo: Long) =
        DeribitMarkTrade(minuteNo, minuteNo * minute + minute / 2, BigDecimal("84005"), BigDecimal("84000"))

    private fun printed(trade: DeribitMarkTrade) =
        DeribitPublicTrade(
            "paper-${trade.seq}",
            trade.seq,
            trade.timestampMs,
            BigDecimal("84005"),
            BigDecimal("0.01"),
            if (trade.seq % 2 == 0L) "buy" else "sell",
            "T".takeIf { trade.seq % 10 == 0L },
        )

    private fun listed(
        name: String,
        expiryMs: Long?,
        strike: String?,
    ) = DeribitInstrument(
        name,
        if (strike == null) "future" else "option",
        expiryMs == null,
        expiryMs,
        strike?.let(::BigDecimal),
        strike?.let { "put" },
        BigDecimal.ONE,
        BigDecimal("0.5"),
        BigDecimal("0.01"),
        "USDC",
        "btc_usdc",
    )

    /**
     * The perpetual is quoted both sides, with its open interest; the option has no ask, so buying it is refused,
     * and a mark IV and forward.
     */
    private fun quoted(name: String): DeribitTicker {
        val bid = if (name == perp) BigDecimal("84000") else BigDecimal("100")
        val ask = if (name == perp) BigDecimal("84000.5") else null
        return DeribitTicker(
            name,
            System.currentTimeMillis(),
            bid,
            BigDecimal.TEN,
            ask,
            ask?.let {
                BigDecimal.TEN
            },
            null,
            BigDecimal("52.3").takeIf { name == put },
            BigDecimal("84010").takeIf { name == put },
            null,
            openInterest = if (name == perp) BigDecimal("1477.6341") else null,
        )
    }

    override fun newAdapter(stateDir: Path) =
        PaperAdapter(
            AdapterContext(mapOf("starting_balance" to "10000"), System::currentTimeMillis, stateDir),
            market,
        ) { onTicker, onConnection ->
            object : DeribitTickers {
                override fun start() = onConnection(true, "open")

                override fun subscribe(names: Set<String>) = names.forEach { onTicker(quoted(it)) }

                override fun close() {}
            }
        }

    override fun restingOrder(clientOrderId: String) =
        NewOrder(
            clientOrderId,
            perp,
            Side.BUY,
            OrderType.LIMIT,
            BigDecimal("0.01"),
            BigDecimal("40000"),
            null,
            TimeInForce.GTC,
        )

    override fun fillingOrder(clientOrderId: String) =
        NewOrder(clientOrderId, perp, Side.BUY, OrderType.MARKET, BigDecimal("0.01"), null, null, TimeInForce.IOC)

    override fun closingOrder(clientOrderId: String) =
        NewOrder(
            clientOrderId,
            perp,
            Side.SELL,
            OrderType.MARKET,
            BigDecimal("0.01"),
            null,
            null,
            TimeInForce.IOC,
            reduceOnly = true,
        )

    override fun refusedOrder(clientOrderId: String) =
        NewOrder(clientOrderId, put, Side.BUY, OrderType.MARKET, BigDecimal.ONE, null, null, TimeInForce.IOC)

    override val activeCode = perp

    override val perpetualCode = perp

    override val optionCode = put

    private companion object {
        const val EXPIRY_MS = 2_555_884_800_000L
        const val HOUR_MS = 3_600_000L
    }
}
