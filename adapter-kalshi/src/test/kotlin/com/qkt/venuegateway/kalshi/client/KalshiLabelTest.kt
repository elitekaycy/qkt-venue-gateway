package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.adapter.VenueRefusedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Labels as Kalshi's `client_order_id`. Probed on the demo on 2026-11-16: an id with `.` or `~` is
 * `400 invalid_parameters`; `_`, `-`, `:`, `=` and 64 characters are taken. qkt's own labels end `.<suffix>`.
 */
class KalshiLabelTest {
    @Test
    fun `a label of what Kalshi takes is sent as it is`() {
        assertThat(KalshiLabel.encode("ORD-atto_event_settle-0")).isEqualTo("ORD-atto_event_settle-0")
        assertThat(KalshiLabel.encode("kit:A-1_b")).isEqualTo("kit:A-1_b")
        assertThat(KalshiLabel.decode("qkt-probe-rest")).isEqualTo("qkt-probe-rest")
    }

    @Test
    fun `a dot, the escape itself and any other character are sent as hex and read back`() {
        assertThat(KalshiLabel.encode("ORD-atto_settle-0.mwjckoij")).isEqualTo("ORD-atto_settle-0=2Emwjckoij")
        assertThat(KalshiLabel.encode("a=b~c")).isEqualTo("a=3Db=7Ec")
        assertThat(KalshiLabel.encode("é")).isEqualTo("=C3=A9")
        listOf("ORD-atto_event_settle-0.mwjckoij", "a=b~c", "é/ x", "=", "a.b.c=3D").forEach { label ->
            assertThat(KalshiLabel.decode(KalshiLabel.encode(label))).describedAs(label).isEqualTo(label)
        }
    }

    @Test
    fun `an id nobody encoded is read as it is`() {
        assertThat(KalshiLabel.decode("made=elsewhere")).isEqualTo("made=elsewhere")
        assertThat(KalshiLabel.decode("ends=2")).isEqualTo("ends=2")
        assertThat(KalshiLabel.decode("ends=")).isEqualTo("ends=")
        assertThat(KalshiLabel.decode("lower=2e")).isEqualTo("lower=2e")
    }

    @Test
    fun `a label that would pass 64 characters once encoded is refused by name`() {
        val fits = "a".repeat(64)
        assertThat(KalshiLabel.encode(fits)).isEqualTo(fits)
        assertThatThrownBy { KalshiLabel.encode("a".repeat(62) + ".") }
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("at most 64 characters")
            .hasMessageContaining("needs 65")
    }
}
