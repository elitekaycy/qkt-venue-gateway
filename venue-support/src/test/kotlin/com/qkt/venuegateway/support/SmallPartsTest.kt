package com.qkt.venuegateway.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SmallPartsTest {
    @Test
    fun `retry stops at the first answer and gives up after its attempts`() {
        var n = 0
        val slept = mutableListOf<Long>()
        assertThat(Retry.until(5, 50, slept::add) { if (++n == 3) "found" else null }).isEqualTo("found")
        assertThat(slept).containsExactly(50L, 50L)
        assertThat(Retry.until<String>(2, 1, {}) { null }).isNull()
    }

    @Test
    fun `hmac sha256 matches the RFC 4231 test vector`() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?"
        assertThat(Hmac.sha256Hex("Jefe", "what do ya want for nothing?"))
            .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")
    }

    @Test
    fun `a subscription set answers only what changed and keeps the rest for a reconnect`() {
        val set = SubscriptionSet<String>()
        assertThat(set.want(listOf("a", "b"))).containsExactly("a", "b")
        assertThat(set.want(listOf("b", "c"))).containsExactly("c")
        assertThat(set.drop(listOf("a", "z"))).containsExactly("a")
        assertThat(set.all()).containsExactly("b", "c")
    }
}
