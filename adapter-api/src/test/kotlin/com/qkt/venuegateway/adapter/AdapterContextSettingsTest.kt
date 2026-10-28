package com.qkt.venuegateway.adapter

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AdapterContextSettingsTest {
    private enum class Environment { TESTNET, MAINNET }

    private fun context(vararg settings: Pair<String, String>) = AdapterContext(mapOf(*settings), { 0L }, Path.of("."))

    @Test
    fun `typed settings read, default, and refuse bad values naming the key`() {
        val c =
            context(
                "environment" to "Testnet",
                "depth" to "25",
                "bad_int" to "x",
                "fee" to "0.0005",
                "flag" to "TRUE",
            )
        assertThat(c.enum("environment", Environment.entries.toTypedArray())).isEqualTo(Environment.TESTNET)
        assertThat(c.int("depth")).isEqualTo(25)
        assertThat(c.int("absent", 7)).isEqualTo(7)
        assertThat(c.decimal("fee")).isEqualByComparingTo("0.0005")
        assertThat(c.bool("flag")).isTrue
        assertThat(c.text("absent", "d")).isEqualTo("d")
        assertThatThrownBy { c.int("bad_int") }.hasMessage("setting bad_int must be a whole number: x")
        assertThatThrownBy { c.long("absent") }.hasMessage("setting absent is required")
        assertThatThrownBy { context("environment" to "live").enum("environment", Environment.entries.toTypedArray()) }
            .hasMessage("setting environment must be one of testnet, mainnet: live")
    }
}
