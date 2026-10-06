package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.bybit.BybitErrors.venue
import com.qkt.venuegateway.bybit.client.BybitPosition

/**
 * How the account holds each contract, read from Bybit, never assumed: a contract in one-way mode has one
 * position slot (index 0), one in hedge mode two (1 long, 2 short), and Bybit sets the mode per contract or
 * per settle coin. A contract's mode is read from its slots ([slots]) and kept [ttlMs] (Bybit lets it change
 * only while the contract is flat with nothing working). The account is reported hedging when any contract it
 * holds is held by side, else as its [reference] contract is set (the coin's BTC perpetual, which a switch by
 * coin moves with the rest). A spot account holds coins, netted.
 */
internal class BybitPositionModes(
    private val slots: (symbol: String) -> List<BybitPosition>,
    private val reference: () -> String?,
    private val contracts: Boolean,
    private val clock: () -> Long,
    private val ttlMs: Long = 600_000,
) {
    private val hedged = HashMap<String, Pair<Boolean, Long>>()

    /** The position index an order on a contract is sent with: 0 one-way; hedged, the slot it opens or reduces. */
    fun index(order: NewOrder): Int? {
        if (!contracts) return null
        if (!hedge(order.symbol)) return 0
        val long = (order.side == Side.BUY) != order.reduceOnly
        return if (long) 1 else 2
    }

    /** How the account holds [rows], the account's open positions. */
    fun accounting(rows: List<PositionRow>): Accounting =
        when {
            !contracts -> Accounting.NETTING
            rows.any { it.ticket != null } -> Accounting.HEDGING
            reference()?.let(::hedge) == true -> Accounting.HEDGING
            else -> Accounting.NETTING
        }

    /** Forgets [symbol]'s mode, after Bybit said an order's position index did not match it. */
    fun forget(symbol: String) = synchronized(hedged) { hedged.remove(symbol) }

    private fun hedge(symbol: String): Boolean {
        synchronized(hedged) { hedged[symbol]?.takeIf { clock() - it.second < ttlMs }?.let { return it.first } }
        val read = venue { slots(symbol) }
        check(read.isNotEmpty()) { "bybit answered no position slot of $symbol" }
        val hedge = read.any { it.positionIdx != 0 }
        synchronized(hedged) { hedged[symbol] = hedge to clock() }
        return hedge
    }
}
