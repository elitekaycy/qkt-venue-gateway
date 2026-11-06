package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.StrikeType
import com.qkt.venuegateway.adapter.Tradability
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KalshiTermsTest {
    private val market = Fixtures.market("public/events-page-2.json", 1)

    @Test
    fun `every strike type Kalshi documents has its value, and an unknown one fails`() {
        val words =
            listOf("greater", "greater_or_equal", "less", "less_or_equal", "between", "functional", "structured")
        assertThat(words.map(KalshiTerms::strikeType)).containsExactly(
            StrikeType.GREATER,
            StrikeType.GREATER_OR_EQUAL,
            StrikeType.LESS,
            StrikeType.LESS_OR_EQUAL,
            StrikeType.BETWEEN,
            StrikeType.CUSTOM,
            StrikeType.CUSTOM,
        )
        assertThat(KalshiTerms.strikeType("custom")).isEqualTo(StrikeType.CUSTOM)
        assertThat(KalshiTerms.strikeType(null)).isEqualTo(StrikeType.NONE)
        assertThatThrownBy { KalshiTerms.strikeType("sideways") }.hasMessageContaining("strike_type sideways")
    }

    @Test
    fun `each series cadence is normalised, and words Kalshi does not fix read as custom`() {
        val words = listOf("fifteen_min", "hourly", "daily", "weekly", "monthly", "quarterly", "annual", "one_off")
        assertThat(words.map(KalshiTerms::frequency))
            .containsExactly("15m", "1h", "1d", "1w", "1mo", "1q", "1y", "one_off")
        assertThat(KalshiTerms.frequency("one_time")).isEqualTo("custom")
        assertThat(KalshiTerms.frequency(null)).isNull()
    }

    @Test
    fun `fees scale with the multiplier; maker fees only where the fee type says`() {
        val half = BigDecimal("0.5")
        assertThat(KalshiTerms.fee("quadratic", half, false)!!.takerRate).isEqualByComparingTo("0.035")
        assertThat(KalshiTerms.fee("quadratic_with_maker_fees", half, false)!!.makerRate)
            .isEqualByComparingTo("0.00875")
        assertThat(KalshiTerms.fee("quadratic_with_combo_maker_fees", BigDecimal.ONE, false)!!.makerRate).isNull()
        assertThat(KalshiTerms.fee("quadratic_with_combo_maker_fees", BigDecimal.ONE, true)!!.makerRate)
            .isEqualByComparingTo("0.0175")
        assertThat(KalshiTerms.fee("flat", BigDecimal.ONE, false)).isNull()
        assertThat(KalshiTerms.fee("quadratic", null, false)).isNull()
    }

    @Test
    fun `an outcome is 1 for yes, 0 for no, the settlement value for a scalar; an unknown result fails`() {
        assertThat(KalshiTerms.outcome(market.copy(result = "yes"))).isEqualByComparingTo("1")
        assertThat(KalshiTerms.outcome(market.copy(result = "scalar", settlementValue = BigDecimal("0.37"))))
            .isEqualByComparingTo("0.37")
        assertThatThrownBy { KalshiTerms.outcome(market.copy(result = "void")) }.hasMessageContaining("result void")
    }

    @Test
    fun `only an active market trades in full; closed closes only; combos and unopened markets are disabled`() {
        assertThat(KalshiTerms.trading(market, 0)).isEqualTo(Tradability.FULL)
        assertThat(KalshiTerms.trading(market.copy(status = "closed"), 0)).isEqualTo(Tradability.CLOSE_ONLY)
        listOf("initialized", "inactive", "determined", "disputed", "amended", "finalized", "settled").forEach {
            assertThat(KalshiTerms.trading(market.copy(status = it), 0)).describedAs(it).isEqualTo(Tradability.DISABLED)
        }
        assertThat(KalshiTerms.trading(market.copy(mveCollection = "KXMVECROSSCATEGORY-R"), 0))
            .isEqualTo(Tradability.DISABLED)
        assertThat(KalshiTerms.trading(market.copy(ticker = "KXMVESPORTS-X"), 0)).isEqualTo(Tradability.DISABLED)
        assertThat(KalshiTerms.trading(market.copy(exchangeIndex = null), 2)).isEqualTo(Tradability.FULL)
        assertThatThrownBy { KalshiTerms.trading(market.copy(status = "frozen"), 0) }
            .hasMessageContaining("status frozen")
    }
}
