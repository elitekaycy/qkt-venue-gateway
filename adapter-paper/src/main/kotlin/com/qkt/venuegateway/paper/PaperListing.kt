package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import java.math.BigDecimal

/**
 * The instruments the paper venue trades: Deribit's live [currency] perpetuals, futures and options,
 * re-read at most every [refreshMs]. An option's `underlying` is its name's first field
 * (`BTC_USDC-9OCT26-82000-P` → `BTC_USDC`), the root clients subscribe to. A contract that expired
 * leaves the live listing but is still looked up one by one ([held]), so a held position settles.
 * Deribit takes the amount of a linear (USDC) contract in its base coin, so one unit of quantity is one
 * coin: every instrument is listed with contract size 1, and Deribit's own `contract_size` becomes the
 * volume step.
 */
class PaperListing(
    private val market: DeribitMarketData,
    private val currency: String,
    private val clock: () -> Long,
    private val refreshMs: Long = 600_000,
) {
    @Volatile private var byName: Map<String, DeribitInstrument> = emptyMap()

    @Volatile private var readAt: Long? = null
    private val archived = java.util.concurrent.ConcurrentHashMap<String, DeribitInstrument>()

    /** Every listed instrument, refreshed when due. */
    fun all(): Collection<DeribitInstrument> = current().values

    /** [name]'s listing, re-reading when it is not known (a contract listed since), at most once a minute. */
    fun find(name: String): DeribitInstrument? {
        current()[name]?.let { return it }
        archived[name]?.let { return it }
        val last = readAt
        return if (last != null && clock() - last < MISS_RELOAD_MS) null else reload()[name]
    }

    /** [name]'s listing even after it expired and left the live listing (a held contract to settle). */
    fun held(name: String): DeribitInstrument = find(name) ?: archived.getOrPut(name) { market.instrument(name) }

    /** The listed options of [root]. */
    fun optionsOf(root: String): List<String> =
        all().filter { it.kind == "option" && underlying(it) == root }.map { it.name }

    /** [i] as the adapter interface states it. */
    fun neutral(i: DeribitInstrument): Instrument =
        Instrument(
            code = i.name,
            kind =
                when {
                    i.kind == "option" -> InstrumentKind.OPTION
                    i.perpetual -> InstrumentKind.PERPETUAL
                    else -> InstrumentKind.FUTURE
                },
            currency = i.settlementCurrency,
            contractSize = BigDecimal.ONE,
            tickSize = i.tickSize,
            volumeStep = i.contractSize,
            volumeMin = i.minTradeAmount,
            expiryMs = i.expiryMs,
            strike = i.strike,
            right = i.optionType,
            underlying = if (i.kind == "option") underlying(i) else null,
        )

    private fun underlying(i: DeribitInstrument) = i.name.substringBefore('-')

    private companion object {
        const val MISS_RELOAD_MS = 60_000L
    }

    private fun current(): Map<String, DeribitInstrument> {
        val last = readAt
        return if (last == null || clock() - last >= refreshMs) reload() else byName
    }

    @Synchronized
    private fun reload(): Map<String, DeribitInstrument> {
        byName =
            (market.instruments(currency, "future") + market.instruments(currency, "option")).associateBy { it.name }
        readAt = clock()
        return byName
    }
}
