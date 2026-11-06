package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.EventTerms
import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.StrikeType
import com.qkt.venuegateway.kalshi.client.KalshiEvent
import com.qkt.venuegateway.kalshi.client.KalshiMarket
import com.qkt.venuegateway.kalshi.client.KalshiSeries
import java.math.BigDecimal

/** One listed market with the event and series it belongs to (the series null when Kalshi has none by its ticker). */
internal data class KalshiListed(
    val market: KalshiMarket,
    val event: KalshiEvent,
    val series: KalshiSeries?,
    val instrument: Instrument,
)

/**
 * A Kalshi market as an [InstrumentKind.EVENT] instrument: its ticker as the code, paying 1 USD per contract, priced
 * on the finest step of its `price_ranges`, in steps of 0.01 contract (the demo took a 1.5-contract order on a
 * whole-cent market; Kalshi no longer reports which markets trade fractions), expiring when the result is expected.
 * The question reads the strike type against the bound it uses: `greater*` the floor only, `less*` the cap only.
 */
internal object KalshiMapping {
    private val CONTRACT = BigDecimal.ONE
    private val STEP = BigDecimal("0.01")
    private val CENT = BigDecimal("0.01")

    fun listed(
        market: KalshiMarket,
        event: KalshiEvent,
        series: KalshiSeries?,
        shard: Int,
    ) = KalshiListed(market, event, series, instrument(market, event, series, shard))

    fun instrument(
        m: KalshiMarket,
        event: KalshiEvent,
        series: KalshiSeries?,
        shard: Int,
    ): Instrument =
        Instrument(
            code = m.ticker,
            kind = InstrumentKind.EVENT,
            currency = "USD",
            contractSize = CONTRACT,
            tickSize = tick(m),
            volumeStep = STEP,
            volumeMin = STEP,
            expiryMs = m.expectedExpirationMs ?: m.closeTimeMs,
            category = series?.category,
            trading = KalshiTerms.trading(m, shard),
            event = terms(m, event, series),
        )

    fun terms(
        m: KalshiMarket,
        event: KalshiEvent,
        series: KalshiSeries?,
    ): EventTerms {
        val type = KalshiTerms.strikeType(m.strikeType)
        val floor = m.floorStrike.takeIf { type in FLOORED }
        val cap = m.capStrike.takeIf { type in CAPPED }
        return EventTerms(
            series = event.seriesTicker,
            event = event.eventTicker,
            strikeType = type,
            floor = floor,
            cap = cap,
            openAtMs = m.openTimeMs,
            closeAtMs = m.closeTimeMs,
            occursAtMs = m.occurrenceMs,
            occasion = occasion(event),
            canCloseEarly = m.canCloseEarly,
            exclusive = event.mutuallyExclusive,
            frequency = KalshiTerms.frequency(series?.frequency),
            title = m.yesSubTitle,
            outcome = KalshiTerms.outcome(m),
            fee =
                KalshiTerms.fee(
                    event.feeTypeOverride ?: series?.feeType,
                    event.feeMultiplierOverride ?: series?.feeMultiplier,
                    KalshiTerms.combo(m),
                ),
        )
    }

    /** The finest step of [m]'s price grid; a market without one trades whole cents. */
    fun tick(m: KalshiMarket): BigDecimal = m.priceRanges.minOfOrNull { it.step } ?: CENT

    /** The event ticker after its series prefix (`KXNBAGAME-26NOV08MIASAC` → `26NOV08MIASAC`), when it has one. */
    private fun occasion(event: KalshiEvent): String? =
        event.eventTicker.removePrefix("${event.seriesTicker}-").takeIf { it != event.eventTicker && it.isNotEmpty() }

    private val FLOORED = setOf(StrikeType.GREATER, StrikeType.GREATER_OR_EQUAL, StrikeType.BETWEEN)
    private val CAPPED = setOf(StrikeType.LESS, StrikeType.LESS_OR_EQUAL, StrikeType.BETWEEN)
}
