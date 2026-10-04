package com.qkt.venuegateway.adapter

import java.nio.file.Path

/**
 * One venue account, as the gateway host sees it. The host owns everything the client sees (HTTP,
 * WebSocket, the journal, the kill switch); an adapter only translates between its venue's API and
 * these calls. Rules every adapter keeps:
 * - [place] sends the order's `clientOrderId` as the venue's order label, so [orderByLabel] finds it
 *   after any crash; a venue without such a field cannot be adapted.
 * - fills carry the venue's own execution id; pushes may arrive late, twice or out of order (the host
 *   orders and de-duplicates them), and are never dropped silently.
 * - a refusal by the venue is [VenueRefusedException]; the venue being unreachable is
 *   [VenueUnavailableException]; nothing else is thrown for an expected condition.
 */
interface VenueAdapter : AutoCloseable {
    /** The adapter's name, reported as the gateway's `adapter` (e.g. `deribit`, `paper`). */
    val id: String

    /** The adapter's version. */
    val version: String

    /** The optional services this adapter serves; by default the three every v1 adapter served. */
    val capabilities: Set<Capability> get() = setOf(Capability.BARS, Capability.QUOTES, Capability.SETTLEMENTS)

    /**
     * Opens the venue link and returns at once; pushes go to [listener] from then on. The adapter
     * reports `connection(true)` once it can serve calls (the host takes no orders before it), and
     * `connection(false)` whenever it no longer can.
     */
    fun connect(listener: AdapterListener)

    fun identity(): VenueIdentity

    fun instruments(): List<Instrument>

    fun account(): AccountSnapshot

    fun positions(): Positions

    fun openOrders(): List<VenueOrder>

    fun place(order: NewOrder): VenueOrder

    /** Cancels the order labelled [clientOrderId]; null when the venue has no such order. */
    fun cancel(clientOrderId: String): VenueOrder?

    /** Changes the order labelled [clientOrderId]; null when the venue has no such order. */
    fun modify(
        clientOrderId: String,
        change: OrderChange,
    ): VenueOrder?

    /** The order labelled [clientOrderId], or null when the venue never saw it. */
    fun orderByLabel(clientOrderId: String): VenueOrder?

    /** Executions from [fromMs] to [toMs], oldest first. */
    fun fills(
        fromMs: Long,
        toMs: Long,
    ): List<VenueFill>

    /** Contract settlements from [fromMs] to [toMs], oldest first. */
    fun settlements(
        fromMs: Long,
        toMs: Long,
    ): List<VenueSettlement>

    /** Funding charged or credited from [fromMs] to [toMs], oldest first; only with [Capability.FUNDING]. */
    fun funding(
        fromMs: Long,
        toMs: Long,
    ): List<VenueFunding> = throw VenueUnsupportedException("funding")

    /** Published funding rates of perpetual [code] from [fromMs] to [toMs], oldest first; only with [Capability.FUNDING_RATES]. */
    fun fundingRates(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueFundingRate> = throw VenueUnsupportedException("funding rates")

    /** Closed bars of [code], [windowMs] long, starting in `[fromMs, toMs)`, oldest first. */
    fun bars(
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
    ): List<VenueBar>

    /** Subscribes the quotes of [codes] and of every listed option of [roots]; pushes go to the listener. */
    fun subscribeQuotes(
        codes: Set<String>,
        roots: Set<String>,
    )
}

/** Where an adapter pushes what the venue reports. */
interface AdapterListener {
    fun order(order: VenueOrder)

    fun fill(fill: VenueFill)

    fun settlement(settlement: VenueSettlement)

    /** Funding the venue charged or credited; an adapter whose venue pushes none reports it from [VenueAdapter.funding]. */
    fun funding(funding: VenueFunding)

    fun quote(quote: VenueQuote)

    /** The venue link went [up] or down, with a [reason] for the logs. */
    fun connection(
        up: Boolean,
        reason: String,
    )
}

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

/** The venue refused a request; [reason] is its own. Served as `422 venue_rejected`. */
class VenueRefusedException(
    val reason: String,
) : RuntimeException(reason)

/** The venue cannot be reached. Served as `503 venue_unavailable`. */
class VenueUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
