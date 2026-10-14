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
    fun required(key: String): String = settings[key]?.takeIf { it.isNotBlank() } ?: error("setting $key is required")

    /** The venue credentials, or a failure naming the missing config. */
    fun requiredCredentials(): Credentials = credentials ?: error("credentials are required")
}

/** Builds the adapter of one adapter type (`GATEWAY_ADAPTER`); found with `java.util.ServiceLoader`. */
interface VenueAdapterFactory {
    val type: String

    fun create(context: AdapterContext): VenueAdapter
}
