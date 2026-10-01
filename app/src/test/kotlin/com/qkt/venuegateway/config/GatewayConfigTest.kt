package com.qkt.venuegateway.config

import com.qkt.venuegateway.host.server.Role
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GatewayConfigTest {
    private val env = mapOf("TRADER" to "t-secret", "GUARD" to "g-secret")

    private fun parse(yaml: String) = GatewayConfig.parse(yaml, env)

    @Test
    fun `a full config resolves tokens by role and keeps adapter settings as written`(
        @TempDir dir: Path,
    ) {
        val guardFile = dir.resolve("guard.token").also { Files.writeString(it, "g-file\n") }
        val config =
            parse(
                """
                listen: 127.0.0.1:8443
                state_dir: ${dir.resolve("state")}
                tokens:
                  trader: env:TRADER
                  guardian: file:$guardFile
                adapter:
                  type: paper
                  settings:
                    starting_balance: "10000"
                    login: paper-1
                """.trimIndent(),
            )

        assertThat(config.host).isEqualTo("127.0.0.1")
        assertThat(config.port).isEqualTo(8443)
        assertThat(config.tokens).isEqualTo(mapOf(Role.TRADER to "t-secret", Role.GUARDIAN to "g-file"))
        assertThat(config.adapter).isEqualTo("paper")
        assertThat(config.settings).containsEntry("starting_balance", "10000").containsEntry("login", "paper-1")
    }

    @Test
    fun `a missing key, an inline token or an unset variable is refused by name`() {
        val base = "listen: 127.0.0.1:8443\nstate_dir: /tmp/s\nadapter:\n  type: paper\n"
        assertThatThrownBy { parse(base) }.hasMessageContaining("tokens.trader")
        assertThatThrownBy { parse(base + "tokens:\n  trader: plain\n  guardian: env:GUARD\n") }
            .hasMessageContaining("tokens.trader must be env:<VAR> or file:<path>")
        assertThatThrownBy { parse(base + "tokens:\n  trader: env:NOPE\n  guardian: env:GUARD\n") }
            .hasMessageContaining("NOPE is not set")
        assertThatThrownBy { parse("state_dir: /tmp/s\ntokens: {}\n") }.hasMessageContaining("listen")
    }
}
