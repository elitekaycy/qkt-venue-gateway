package com.qkt.venuegateway.config

import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.host.server.Role
import java.nio.file.Files
import java.nio.file.Path

/**
 * One gateway's configuration: where it listens ([host]:[port]), where its journal lives ([stateDir]),
 * each role's token, and the [adapter] serving the account with its [settings] and venue [credentials].
 *
 * It is read from `GATEWAY_*` environment variables by [fromEnv]; every default is in [DEFAULTS], and a
 * variable without one is required only where named below:
 *
 * | Variable | Default | |
 * |---|---|---|
 * | `GATEWAY_LISTEN` | `127.0.0.1:8443` | `<host>:<port>` to serve VGP on |
 * | `GATEWAY_STATE_DIR` | `./state` | the journal and the adapter's own state |
 * | `GATEWAY_ADAPTER` | `paper` | the adapter type |
 * | `GATEWAY_TRADER_TOKEN`, `GATEWAY_GUARDIAN_TOKEN` | required | each role's bearer token |
 * | `GATEWAY_LOGIN`, `GATEWAY_SECRET` | none | the venue credentials, both or neither |
 * | `GATEWAY_PLUGINS_DIR` | none | adapter jars beside the built-in ones |
 * | `GATEWAY_SETTING_<KEY>` | the adapter's | adapter setting `<key>`, lower-cased |
 *
 * Any variable but a setting may instead be given as `<NAME>_FILE`, the path of a file holding the
 * value (a Docker secret), but not both.
 */
data class GatewayConfig(
    val host: String,
    val port: Int,
    val stateDir: Path,
    val tokens: Map<Role, String>,
    val adapter: String,
    val settings: Map<String, String>,
    val pluginsDir: Path? = null,
    val credentials: Credentials? = null,
) {
    /** Every field but the role tokens, which are secrets and never printed. */
    override fun toString() =
        "GatewayConfig(host=$host, port=$port, stateDir=$stateDir, tokens=${tokens.keys}, adapter=$adapter, " +
            "settings=$settings, pluginsDir=$pluginsDir, credentials=$credentials)"

    companion object {
        /** The prefix of every adapter setting's variable: `GATEWAY_SETTING_STOP_TRIGGER` is `stop_trigger`. */
        const val SETTING_PREFIX = "GATEWAY_SETTING_"

        /** The value each variable takes when unset. */
        val DEFAULTS =
            mapOf(
                "GATEWAY_LISTEN" to "127.0.0.1:8443",
                "GATEWAY_STATE_DIR" to "./state",
                "GATEWAY_ADAPTER" to "paper",
            )

        /** The config [env] describes; fails naming the variable at fault. */
        fun fromEnv(env: Map<String, String>): GatewayConfig {
            val vars = Vars(env)
            val listen = vars.withDefault("GATEWAY_LISTEN")
            require(listen.contains(':')) { "GATEWAY_LISTEN must be <host>:<port>: $listen" }
            return GatewayConfig(
                host = listen.substringBeforeLast(':'),
                port = listen.substringAfterLast(':').toIntOrNull() ?: error("GATEWAY_LISTEN has no port: $listen"),
                stateDir = Path.of(vars.withDefault("GATEWAY_STATE_DIR")),
                tokens = Role.entries.associateWith { vars.required("GATEWAY_${it.key.uppercase()}_TOKEN") },
                adapter = vars.withDefault("GATEWAY_ADAPTER"),
                settings = settings(env),
                pluginsDir = vars.optional("GATEWAY_PLUGINS_DIR")?.let { Path.of(it) },
                credentials = credentials(vars),
            )
        }

        private fun settings(env: Map<String, String>): Map<String, String> =
            env
                .filterKeys { it.startsWith(SETTING_PREFIX) && it.length > SETTING_PREFIX.length }
                .filterValues { it.isNotBlank() }
                .mapKeys { it.key.removePrefix(SETTING_PREFIX).lowercase() }

        private fun credentials(vars: Vars): Credentials? {
            val login = vars.optional("GATEWAY_LOGIN")
            val secret = vars.optional("GATEWAY_SECRET")
            return when {
                login == null && secret == null -> null
                login == null -> error("GATEWAY_LOGIN is required with GATEWAY_SECRET")
                secret == null -> error("GATEWAY_SECRET is required with GATEWAY_LOGIN")
                else -> Credentials(login, secret)
            }
        }
    }

    /** Variables read from [env], each set directly or through `<NAME>_FILE`. */
    private class Vars(
        private val env: Map<String, String>,
    ) {
        fun optional(name: String): String? {
            val direct = env[name]?.takeIf { it.isNotBlank() }
            val file = env["${name}_FILE"]?.takeIf { it.isNotBlank() }
            require(direct == null || file == null) { "set $name or ${name}_FILE, not both" }
            val value = direct ?: file?.let { Files.readString(Path.of(it)).trim() } ?: return null
            require(value.isNotBlank()) { "${name}_FILE names an empty file: $file" }
            return value
        }

        fun required(name: String): String = optional(name) ?: error("$name (or ${name}_FILE) is required")

        fun withDefault(name: String): String = optional(name) ?: DEFAULTS.getValue(name)
    }
}
