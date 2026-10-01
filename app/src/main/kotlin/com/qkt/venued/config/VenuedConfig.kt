package com.qkt.venued.config

import java.nio.file.Files
import java.nio.file.Path
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/** Who a token belongs to: qkt trades with [TRADER]; guardrails watches and flips the kill switch as [GUARDIAN]. */
enum class Role(
    val key: String,
) {
    TRADER("trader"),
    GUARDIAN("guardian"),
}

/**
 * One gateway's configuration: where it listens ([host]:[port]), where its journal lives ([stateDir]),
 * each role's token, and the [adapter] serving the account with its [settings]. Tokens are references,
 * `env:<VAR>` or `file:<path>`, never inline, so a config file carries no secret.
 *
 * ```yaml
 * listen: 127.0.0.1:8443
 * state_dir: /var/lib/qkt-venued/deribit
 * tokens: { trader: env:VENUED_TRADER_TOKEN, guardian: file:/run/secrets/guardian }
 * adapter: { type: paper, settings: { starting_balance: "10000" } }
 * ```
 */
data class VenuedConfig(
    val host: String,
    val port: Int,
    val stateDir: Path,
    val tokens: Map<Role, String>,
    val adapter: String,
    val settings: Map<String, String>,
) {
    companion object {
        /** [yaml] as a config, resolving `env:` references against [env]; fails naming the key at fault. */
        fun parse(
            yaml: String,
            env: Map<String, String>,
        ): VenuedConfig {
            val root = Load(LoadSettings.builder().build()).loadFromString(yaml) as? Map<*, *> ?: emptyMap<Any, Any>()
            val listen = text(root, "listen")
            require(listen.contains(':')) { "listen must be <host>:<port>: $listen" }
            val tokens = section(root, "tokens")
            val adapter = section(root, "adapter")
            return VenuedConfig(
                host = listen.substringBeforeLast(':'),
                port = listen.substringAfterLast(':').toIntOrNull() ?: error("listen has no port: $listen"),
                stateDir = Path.of(text(root, "state_dir")),
                tokens = Role.entries.associateWith { secret(tokens, it.key, env) },
                adapter = text(adapter, "type", "adapter."),
                settings = section(adapter, "settings").entries.associate { (k, v) -> k.toString() to v.toString() },
            )
        }

        private fun text(
            map: Map<*, *>,
            key: String,
            path: String = "",
        ): String = map[key]?.toString()?.takeIf { it.isNotBlank() } ?: error("$path$key is required")

        private fun section(
            map: Map<*, *>,
            key: String,
        ): Map<*, *> = map[key] as? Map<*, *> ?: emptyMap<Any, Any>()

        private fun secret(
            tokens: Map<*, *>,
            role: String,
            env: Map<String, String>,
        ): String {
            val ref = text(tokens, role, "tokens.")
            val value =
                when {
                    ref.startsWith("env:") ->
                        env[ref.removePrefix("env:")]
                            ?: error("${ref.removePrefix("env:")} is not set")
                    ref.startsWith("file:") -> Files.readString(Path.of(ref.removePrefix("file:"))).trim()
                    else -> error("tokens.$role must be env:<VAR> or file:<path>")
                }
            require(value.isNotBlank()) { "tokens.$role resolves to an empty token" }
            return value
        }
    }
}
