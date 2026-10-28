package com.qkt.venuegateway.adapter

import java.nio.file.Path

/**
 * The venue login every adapter receives in one shape, whatever its venue calls the two halves: an
 * API key's client id and secret, or a username and password. [login] is not secret and is what the
 * adapter reports as its identity's login; [secret] never leaves the adapter and never prints.
 */
data class Credentials(
    val login: String,
    val secret: String,
) {
    override fun toString() = "Credentials(login=$login, secret=***)"
}

/**
 * What the host hands an adapter: its config [settings], its venue [credentials] (null when the
 * gateway was given none, as for the paper venue), a [clock] and a private [stateDir].
 */
class AdapterContext(
    val settings: Map<String, String>,
    val clock: () -> Long,
    val stateDir: Path,
    val credentials: Credentials? = null,
) {
    /** Setting [key], or a failure naming it. */
    fun required(key: String): String =
        settings[key]?.takeIf { it.isNotBlank() } ?: error("${variable(key)} is required")

    /** Setting [key], or [default] when it is not set. */
    fun text(
        key: String,
        default: String,
    ): String = settings[key]?.takeIf { it.isNotBlank() } ?: default

    /** Setting [key] as one of [values] (case ignored), or [default]; no default makes it required. */
    fun <E : Enum<E>> enum(
        key: String,
        values: Array<E>,
        default: E? = null,
    ): E {
        val raw = settings[key]?.takeIf { it.isNotBlank() } ?: return default ?: error("${variable(key)} is required")
        return values.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: error("${variable(key)} must be one of ${values.joinToString { it.name.lowercase() }}: $raw")
    }

    fun int(
        key: String,
        default: Int? = null,
    ): Int = parsed(key, default, "a whole number") { it.toIntOrNull() }

    fun long(
        key: String,
        default: Long? = null,
    ): Long = parsed(key, default, "a whole number") { it.toLongOrNull() }

    fun decimal(
        key: String,
        default: java.math.BigDecimal? = null,
    ): java.math.BigDecimal = parsed(key, default, "a decimal") { it.toBigDecimalOrNull() }

    fun bool(
        key: String,
        default: Boolean? = null,
    ): Boolean = parsed(key, default, "true or false") { it.lowercase().toBooleanStrictOrNull() }

    private fun <T : Any> parsed(
        key: String,
        default: T?,
        what: String,
        parse: (String) -> T?,
    ): T {
        val raw = settings[key]?.takeIf { it.isNotBlank() } ?: return default ?: error("${variable(key)} is required")
        return parse(raw.trim()) ?: error("${variable(key)} must be $what: $raw")
    }

    /** How a setting is named in a failure: the key, as `GATEWAY_SETTING_<KEY>` reaches the adapter. */
    private fun variable(key: String) = "setting $key"

    /** The venue credentials, or a failure naming the missing config. */
    fun requiredCredentials(): Credentials = credentials ?: error("credentials are required")
}

/** Builds the adapter of one adapter type (`GATEWAY_ADAPTER`); found with `java.util.ServiceLoader`. */
interface VenueAdapterFactory {
    val type: String

    fun create(context: AdapterContext): VenueAdapter
}
