package com.qkt.venuegateway

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueAdapterFactory
import com.qkt.venuegateway.config.GatewayConfig
import com.qkt.venuegateway.host.server.Role
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A plugin whose adapter cannot be built from its settings. */
class FailingVenueFactory : VenueAdapterFactory {
    override val type = "failing-venue"

    override fun create(context: AdapterContext): VenueAdapter = error("setting endpoint is required")
}

/** A second plugin claiming the type [TestVenueFactory] already provides. */
class ImpostorVenueFactory : VenueAdapterFactory {
    override val type = "test-venue"

    override fun create(context: AdapterContext): VenueAdapter = error("never built")
}

class AppEdgesTest {
    @TempDir lateinit var dir: Path

    /** A plugins directory holding a jar that registers [factories], and a file that is not a jar. */
    private fun plugins(vararg factories: String): Path {
        val plugins = Files.createDirectories(dir.resolve("plugins"))
        Files.writeString(plugins.resolve("README.txt"), "not a plugin")
        JarOutputStream(Files.newOutputStream(plugins.resolve("plugins.jar"))).use { out ->
            out.putNextEntry(JarEntry("META-INF/services/com.qkt.venuegateway.adapter.VenueAdapterFactory"))
            out.write(factories.joinToString("") { "com.qkt.venuegateway.$it\n" }.toByteArray())
            out.closeEntry()
        }
        return plugins
    }

    @Test
    fun `the gateway takes no arguments, only its variables`() {
        assertThatThrownBy { main(arrayOf("--port", "1")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("qkt-venue-gateway takes no arguments: it is configured by GATEWAY_* variables")
    }

    @Test
    fun `an adapter that cannot be built fails the start naming it and how its settings are set`() {
        val config =
            GatewayConfig(
                host = "127.0.0.1",
                port = 0,
                stateDir = dir.resolve("state"),
                tokens = mapOf(Role.TRADER to "t"),
                adapter = "failing-venue",
                settings = emptyMap(),
                pluginsDir = plugins("FailingVenueFactory"),
            )

        assertThatThrownBy { start(config) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageStartingWith("adapter failing-venue: setting endpoint is required")
            .hasMessageContaining("a setting <key> is GATEWAY_SETTING_<KEY>")
            .hasRootCauseMessage("setting endpoint is required")
    }

    @Test
    fun `two plugins claiming one adapter type are refused, naming both`() {
        val plugins = plugins("TestVenueFactory", "ImpostorVenueFactory")

        assertThatThrownBy { AdapterLoader(plugins) }
            .hasMessageContaining("adapter type 'test-venue' is provided by")
            .hasMessageContaining("TestVenueFactory")
            .hasMessageContaining("ImpostorVenueFactory")
    }

    @Test
    fun `a plugins directory that is missing, a file, or holds no jar adds no adapter`() {
        val empty = Files.createDirectories(dir.resolve("empty"))
        val file = Files.writeString(dir.resolve("plugins.txt"), "x")

        listOf(dir.resolve("missing"), file, empty).forEach {
            assertThat(AdapterLoader(it).factories.keys).contains("paper").doesNotContain("test-venue", "failing-venue")
        }
    }

    @Test
    fun `a bare setting prefix or a blank setting is not a setting`() {
        val env =
            mapOf(
                "GATEWAY_TRADER_TOKEN" to "t",
                "GATEWAY_GUARDIAN_TOKEN" to "g",
                "GATEWAY_SETTING_" to "x",
                "GATEWAY_SETTING_BLANK" to " ",
                "GATEWAY_SETTING_DEPTH" to "10",
                "GATEWAY_LOGIN_FILE" to " ",
            )

        val config = GatewayConfig.fromEnv(env)

        assertThat(config.settings).isEqualTo(mapOf("depth" to "10"))
        assertThat(config.credentials).isNull()
    }
}
