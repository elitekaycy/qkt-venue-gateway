package com.qkt.venuegateway.config

import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.host.server.Role
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GatewayConfigTest {
    private val env = mapOf("TRADER" to "t-secret", "GUARD" to "g-secret", "VENUE_LOGIN" to "client-7")

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

    private val base =
        "listen: 127.0.0.1:8443\nstate_dir: /tmp/s\ntokens: { trader: env:TRADER, guardian: env:GUARD }\n"

    @Test
    fun `adapter credentials resolve as one login and secret pair, and are absent when not configured`(
        @TempDir dir: Path,
    ) {
        val secretFile = dir.resolve("venue.secret").also { Files.writeString(it, "s3cret\n") }

        val config =
            parse(
                base +
                    "adapter:\n  type: deribit\n  credentials: { login: env:VENUE_LOGIN, secret: file:$secretFile }\n",
            )

        assertThat(config.credentials).isEqualTo(Credentials("client-7", "s3cret"))
        assertThat(parse(base + "adapter:\n  type: paper\n").credentials).isNull()
    }

    @Test
    fun `an inline credential, a missing half or an empty value is refused by name`() {
        val adapter = base + "adapter:\n  type: deribit\n  credentials: "
        assertThatThrownBy { parse(adapter + "{ login: env:VENUE_LOGIN, secret: hunter2 }\n") }
            .hasMessageContaining("adapter.credentials.secret must be env:<VAR> or file:<path>")
        assertThatThrownBy { parse(adapter + "{ login: env:VENUE_LOGIN }\n") }
            .hasMessageContaining("adapter.credentials.secret is required")
        assertThatThrownBy { parse(adapter + "{ login: env:VENUE_LOGIN, secret: env:EMPTY }\n") }
            .hasMessageContaining("EMPTY is not set")
    }

    @Test
    fun `credentials never print their secret`() {
        assertThat(Credentials("client-7", "s3cret").toString()).contains("client-7").doesNotContain("s3cret")
    }
}
