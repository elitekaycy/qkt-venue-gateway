package com.qkt.venuegateway.deribit.client

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeribitTradeHistoryTest {
    private fun trade(
        id: String,
        atMs: Long,
    ) = DeribitTrade(
        id,
        "o",
        null,
        "BTC_USDC-PERPETUAL",
        "buy",
        BigDecimal.ONE,
        BigDecimal.TEN,
        BigDecimal.ZERO,
        "USDC",
        atMs,
    )

    @Test
    fun `pages continue from the last trade's millisecond, so trades sharing it are kept once each`() {
        val starts = mutableListOf<Long>()
        val pages =
            mapOf(
                0L to DeribitPage(listOf(trade("a", 5), trade("b", 7)), more = true),
                7L to DeribitPage(listOf(trade("b", 7), trade("c", 7), trade("d", 9)), more = false),
            )

        val trades =
            DeribitTradeHistory.collect(0, 100) { start ->
                starts += start
                pages.getValue(start)
            }

        assertThat(trades.map { it.tradeId }).containsExactly("a", "b", "c", "d")
        assertThat(starts).containsExactly(0L, 7L)
    }

    @Test
    fun `a page that cannot move past its start fails instead of looping`() {
        assertThatThrownBy {
            DeribitTradeHistory.collect(0, 100) { DeribitPage(listOf(trade("x", 0)), more = true) }
        }.hasMessageContaining("does not advance")
    }
}
