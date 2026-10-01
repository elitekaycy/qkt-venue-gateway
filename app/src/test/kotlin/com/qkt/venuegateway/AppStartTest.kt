package com.qkt.venuegateway

import com.qkt.venuegateway.config.GatewayConfig
import com.qkt.venuegateway.host.server.Role
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AppStartTest {
    /** A plugin jar holding only the service registration of [TestVenueFactory]. */
    private fun pluginJar(dir: Path): Path {
        Files.createDirectories(dir)
        val jar = dir.resolve("test-venue.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("META-INF/services/com.qkt.venuegateway.adapter.VenueAdapterFactory"))
            out.write("com.qkt.venuegateway.TestVenueFactory\n".toByteArray())
            out.closeEntry()
        }
        return dir
    }

    @Test
    fun `built-in adapters are found, a plugin jar adds its own, and an unknown type names the installed ones`(
        @TempDir dir: Path,
    ) {
        assertThat(AdapterLoader(null).factories.keys).contains("paper").doesNotContain("test-venue")
        assertThat(AdapterLoader(pluginJar(dir.resolve("plugins"))).factories.keys).contains("paper", "test-venue")
        assertThatThrownBy {
            AdapterLoader(null).factory("rithmic")
        }.hasMessageContaining("installed: ").hasMessageContaining("paper")
    }

    @Test
    fun `a gateway started from its config serves health with the plugin adapter's identity`(
        @TempDir dir: Path,
    ) {
        val config =
            GatewayConfig(
                host = "127.0.0.1",
                port = 0,
                stateDir = dir.resolve("state"),
                tokens = mapOf(Role.TRADER to "t", Role.GUARDIAN to "g"),
                adapter = "test-venue",
                settings = mapOf("login" to "acct-1"),
                pluginsDir = pluginJar(dir.resolve("plugins")),
            )

        start(config).use { running ->
            val body =
                OkHttpClient()
                    .newCall(
                        Request
                            .Builder()
                            .url(
                                "http://127.0.0.1:${running.port}/v1/health",
                            ).header("Authorization", "Bearer t")
                            .build(),
                    ).execute()
                    .use { it.body!!.string() }
            assertThat(
                body,
            ).contains("\"adapter\":\"test-venue\"", "\"account_login\":\"acct-1\"", "\"venue_connected\":true")
        }
    }
}
