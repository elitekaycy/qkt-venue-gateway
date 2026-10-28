package com.qkt.venuegateway.alpaca

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The kline lengths Alpaca serves on the UTC grid, as the host's bar builder needs them. */
class AlpacaKlinesTest {
    private val minute = 60_000L
    private val day = 86_400_000L

    @Test
    fun `every resolution is whole minutes dividing a day, a minute included, and no day`() {
        val resolutions = AlpacaTimeframes.RESOLUTIONS
        assertThat(resolutions).contains(minute)
        assertThat(resolutions).allSatisfy {
            assertThat(it % minute).isZero
            assertThat(day % it).isZero
        }
        assertThat(resolutions).doesNotContain(day)
        assertThat(
            resolutions.map(AlpacaTimeframes::of),
        ).containsExactlyInAnyOrder("1Min", "5Min", "15Min", "30Min", "1Hour")
    }

    @Test
    fun `a recorded hour bar starts on the UTC hour, so hours build on the host's grid`() {
        val bars = Fixtures.obj("bars-stocks-1hour-last.json")["bars"].toString()
        assertThat(Regex(""""t":"[0-9-]+T[0-9]{2}:00:00Z"""").findAll(bars).count()).isPositive
        assertThat(Regex(""""t":"[0-9-]+T[0-9]{2}:(?!00:00)[0-9:]+Z"""").findAll(bars).count()).isZero
        assertThatThrownBy { AlpacaTimeframes.of(90_000) }.hasMessageContaining("90000")
    }
}
