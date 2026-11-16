package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.EventFee
import com.qkt.venuegateway.adapter.FeeModel
import com.qkt.venuegateway.adapter.StrikeType
import com.qkt.venuegateway.adapter.Tradability
import com.qkt.venuegateway.kalshi.client.KalshiMarket
import java.math.BigDecimal

/** Kalshi's words for an event contract's terms, each turned into its adapter-api value; unknown words fail. */
internal object KalshiTerms {
    /** `functional`, `structured` and `custom` ask about something other than a number; absent asks nothing. */
    fun strikeType(word: String?): StrikeType =
        when (word) {
            null -> StrikeType.NONE
            "greater" -> StrikeType.GREATER
            "greater_or_equal" -> StrikeType.GREATER_OR_EQUAL
            "less" -> StrikeType.LESS
            "less_or_equal" -> StrikeType.LESS_OR_EQUAL
            "between" -> StrikeType.BETWEEN
            "functional", "structured", "custom" -> StrikeType.CUSTOM
            else -> error("kalshi strike_type $word is not known")
        }

    /**
     * A series' cadence, normalised. Kalshi documents no fixed set ("there is no fixed value set here"), and the demo
     * lists words like `often`, `test` and `one_time`, so any word outside the known ones reads as `custom`.
     */
    fun frequency(word: String?): String? =
        when (word) {
            null -> null
            "fifteen_min" -> "15m"
            "hourly" -> "1h"
            "daily" -> "1d"
            "weekly" -> "1w"
            "monthly" -> "1mo"
            "quarterly" -> "1q"
            "annual" -> "1y"
            "one_off" -> "one_off"
            else -> "custom"
        }

    /**
     * The fee of [feeType] scaled by [multiplier]: `quadratic` charges takers 0.07 × m × contracts × p × (1 − p);
     * `quadratic_with_maker_fees` also charges makers 0.0175 × m, and `quadratic_with_combo_maker_fees` does on combo
     * markets only. Charges are rounded up to 0.0001 (recorded on the demo: 2 contracts at 0.04 paid 0.0054, one at
     * 0.01 paid 0.0007). An unknown fee type has no fee shown (the charges still arrive on each fill).
     */
    fun fee(
        feeType: String?,
        multiplier: BigDecimal?,
        combo: Boolean,
    ): EventFee? {
        val m = multiplier ?: return null
        val maker =
            when (feeType) {
                "quadratic" -> false
                "quadratic_with_maker_fees" -> true
                "quadratic_with_combo_maker_fees" -> combo
                else -> return null
            }
        return EventFee(FeeModel.PROBABILITY, TAKER * m, if (maker) MAKER * m else null, ROUNDING)
    }

    /** The settled value per contract once known: 1 for `yes`, 0 for `no`, the settlement value for `scalar`. */
    fun outcome(market: KalshiMarket): BigDecimal? =
        when (market.result) {
            null -> null
            "yes" -> BigDecimal.ONE
            "no" -> BigDecimal.ZERO
            "scalar" -> market.settlementValue
            else -> error("kalshi result ${market.result} of ${market.ticker} is not known")
        }

    /**
     * What the account may do with [market]: a combo (request for quote only), a market of another [shard] and a
     * market not open (initialized, paused as `inactive`, or past its close) are `disabled`; a closed market is
     * `close_only`; an active one trades in full.
     */
    fun trading(
        market: KalshiMarket,
        shard: Int,
    ): Tradability {
        val state =
            when (market.status) {
                "active" -> Tradability.FULL
                "closed" -> Tradability.CLOSE_ONLY
                in NOT_OPEN -> Tradability.DISABLED
                else -> error("kalshi market status ${market.status} of ${market.ticker} is not known")
            }
        val elsewhere = market.exchangeIndex != null && market.exchangeIndex != shard
        return if (combo(market) || elsewhere) Tradability.DISABLED else state
    }

    /** Whether [market] is a combo (multivariate) market. */
    fun combo(market: KalshiMarket) = market.mveCollection != null || market.ticker.startsWith(COMBO_PREFIX)

    const val COMBO_PREFIX = "KXMVE"
    private val TAKER = BigDecimal("0.07")
    private val MAKER = BigDecimal("0.0175")
    private val ROUNDING = BigDecimal("0.0001")
    private val NOT_OPEN = setOf("initialized", "inactive", "determined", "disputed", "amended", "finalized", "settled")
}
