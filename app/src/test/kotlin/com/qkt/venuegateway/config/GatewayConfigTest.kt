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
    private val tokens = mapOf("GATEWAY_TRADER_TOKEN" to "t-secret", "GATEWAY_GUARDIAN_TOKEN" to "g-secret")

    @Test
    fun `with only the tokens set, every other variable takes its default`() {
        val config = GatewayConfig.fromEnv(tokens)

        assertThat(config.host).isEqualTo("127.0.0.1")
        assertThat(config.port).isEqualTo(8443)
        assertThat(config.stateDir).isEqualTo(Path.of("./state"))
        assertThat(config.adapter).isEqualTo("paper")
        assertThat(config.tokens).isEqualTo(mapOf(Role.TRADER to "t-secret", Role.GUARDIAN to "g-secret"))
        assertThat(config.settings).isEmpty()
        assertThat(config.pluginsDir).isNull()
        assertThat(config.credentials).isNull()
    }

    @Test
    fun `set variables replace the defaults and settings are keyed by their lower-cased suffix`() {
        val config =
            GatewayConfig.fromEnv(
                tokens +
                    mapOf(
                        "GATEWAY_LISTEN" to "0.0.0.0:9000",
                        "GATEWAY_STATE_DIR" to "/data",
                        "GATEWAY_ADAPTER" to "deribit",
                        "GATEWAY_PLUGINS_DIR" to "/plugins",
                        "GATEWAY_SETTING_ENVIRONMENT" to "testnet",
                        "GATEWAY_SETTING_STOP_TRIGGER" to "mark_price",
                        "GATEWAY_SETTING_BLANK" to "",
                        "OTHER_SETTING_X" to "ignored",
                    ),
            )

        assertThat(config.host).isEqualTo("0.0.0.0")
        assertThat(config.port).isEqualTo(9000)
        assertThat(config.stateDir).isEqualTo(Path.of("/data"))
        assertThat(config.adapter).isEqualTo("deribit")
        assertThat(config.pluginsDir).isEqualTo(Path.of("/plugins"))
        assertThat(config.settings).isEqualTo(mapOf("environment" to "testnet", "stop_trigger" to "mark_price"))
    }

    @Test
    fun `a missing token is refused by name, as is a listen address without a port`() {
        assertThatThrownBy { GatewayConfig.fromEnv(mapOf("GATEWAY_TRADER_TOKEN" to "t")) }
            .hasMessageContaining("GATEWAY_GUARDIAN_TOKEN (or GATEWAY_GUARDIAN_TOKEN_FILE) is required")
        assertThatThrownBy { GatewayConfig.fromEnv(tokens + ("GATEWAY_TRADER_TOKEN" to " ")) }
            .hasMessageContaining("GATEWAY_TRADER_TOKEN")
        assertThatThrownBy { GatewayConfig.fromEnv(tokens + ("GATEWAY_LISTEN" to "0.0.0.0")) }
            .hasMessageContaining("GATEWAY_LISTEN must be <host>:<port>")
        assertThatThrownBy { GatewayConfig.fromEnv(tokens + ("GATEWAY_LISTEN" to "0.0.0.0:x")) }
            .hasMessageContaining("GATEWAY_LISTEN has no port")
    }

    @Test
    fun `a value can come from the file a _FILE variable names, but not from both`(
        @TempDir dir: Path,
    ) {
        val guard = dir.resolve("guard").also { Files.writeString(it, "g-file\n") }
        val secret = dir.resolve("secret").also { Files.writeString(it, "s3cret\n") }
        val env =
            mapOf(
                "GATEWAY_TRADER_TOKEN" to "t",
                "GATEWAY_GUARDIAN_TOKEN_FILE" to guard.toString(),
                "GATEWAY_LOGIN" to "client-7",
                "GATEWAY_SECRET_FILE" to secret.toString(),
            )

        val config = GatewayConfig.fromEnv(env)

        assertThat(config.tokens[Role.GUARDIAN]).isEqualTo("g-file")
        assertThat(config.credentials).isEqualTo(Credentials("client-7", "s3cret"))
        assertThatThrownBy { GatewayConfig.fromEnv(env + ("GATEWAY_GUARDIAN_TOKEN" to "g")) }
            .hasMessageContaining("set GATEWAY_GUARDIAN_TOKEN or GATEWAY_GUARDIAN_TOKEN_FILE, not both")
        val empty = dir.resolve("empty").also { Files.writeString(it, "\n") }
        assertThatThrownBy { GatewayConfig.fromEnv(env + ("GATEWAY_SECRET_FILE" to empty.toString())) }
            .hasMessageContaining("GATEWAY_SECRET_FILE names an empty file")
    }

    @Test
    fun `credentials are both halves or neither`() {
        assertThatThrownBy { GatewayConfig.fromEnv(tokens + ("GATEWAY_LOGIN" to "client-7")) }
            .hasMessageContaining("GATEWAY_SECRET is required with GATEWAY_LOGIN")
        assertThatThrownBy { GatewayConfig.fromEnv(tokens + ("GATEWAY_SECRET" to "s")) }
            .hasMessageContaining("GATEWAY_LOGIN is required with GATEWAY_SECRET")
    }

    @Test
    fun `credentials never print their secret`() {
        assertThat(Credentials("client-7", "s3cret").toString()).contains("client-7").doesNotContain("s3cret")
    }
}
