package com.qkt.venuegateway.alpaca

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AlpacaLabelsTest {
    @Test
    fun `each replacement takes the label's next generation, and every generation reads back as the label`() {
        assertThat(AlpacaLabels.next("ord-1")).isEqualTo("ord-1~r1")
        assertThat(AlpacaLabels.next("ord-1~r1")).isEqualTo("ord-1~r2")
        assertThat(AlpacaLabels.labelOf("ord-1~r12")).isEqualTo("ord-1")
        assertThat(AlpacaLabels.labelOf("ord~r-x")).isEqualTo("ord~r-x")
    }

    @Test
    fun `an oco carries its stop's label after the target's next generation, and stays a pair when replaced`() {
        assertThat(AlpacaLabels.paired("tp-1", "sl-1")).isEqualTo("tp-1~r1~osl-1")
        assertThat(AlpacaLabels.paired("tp-1~r1", "sl-1")).isEqualTo("tp-1~r2~osl-1")
        assertThat(AlpacaLabels.next("tp-1~r1~osl-1")).isEqualTo("tp-1~r2~osl-1")
        assertThat(AlpacaLabels.labelOf("tp-1~r2~osl-1")).isEqualTo("tp-1")
        assertThat(AlpacaLabels.stopOf("tp-1~r2~osl-1")).isEqualTo("sl-1")
        assertThat(AlpacaLabels.stopOf("tp-1~r2")).isNull()
    }

    @Test
    fun `bar lengths Alpaca serves are named its way and any other is refused`() {
        assertThat(AlpacaTimeframes.of(60_000)).isEqualTo("1Min")
        assertThat(AlpacaTimeframes.of(15 * 60_000)).isEqualTo("15Min")
        assertThat(AlpacaTimeframes.of(4 * 3_600_000)).isEqualTo("4Hour")
        assertThat(AlpacaTimeframes.of(86_400_000)).isEqualTo("1Day")
        assertThatThrownBy { AlpacaTimeframes.of(90_000) }.hasMessageContaining("90000")
    }
}
