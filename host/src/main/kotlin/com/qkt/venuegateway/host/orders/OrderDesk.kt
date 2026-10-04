package com.qkt.venuegateway.host.orders

import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.wire.WireMapping
import com.qkt.vgp.WireChange
import com.qkt.vgp.WireOrder
import com.qkt.vgp.WireSubmit
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

/** What the order desk answered: an [Ok] order (new when [Ok.created]), or a [Refused] request with its HTTP status. */
sealed interface DeskResult {
    data class Ok(
        val order: WireOrder,
        val created: Boolean = false,
    ) : DeskResult

    data class Refused(
        val status: Int,
        val code: String,
        val message: String,
    ) : DeskResult
}

/**
 * Orders as VGP v1 defines them (wire spec §3). A submit is idempotent on its `client_order_id`: the
 * same body again returns the stored order, another body or an id written off as dead is `409`. A new
 * one is written ahead to the [journal] before the [venue] sees it, so a crash or a lost answer is
 * resolved on the resend by [OrderRecovery] (the venue's label, else the order's fills), never by
 * placing again while the venue holds a trace of it; that recovery comes before the kill switch, which
 * gates only what would be placed. While the venue is down ([venueUp] false) a submit is `503` before
 * anything is written. Each id is handled under its own lock ([WriteAheads] keeps an in-flight one from
 * being written off).
 */
class OrderDesk(
    private val journal: Journal,
    private val venue: VenueAdapter,
    private val venueUp: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val gate = KillSwitchGate(journal, venue)

    /** Finds what became of a sent order whose answer was lost. */
    val recovery = OrderRecovery(venue, clock)
    private val writeAheads = WriteAheads(journal, recovery, clock)
    private val locks = ConcurrentHashMap<String, Any>()
    private val json = Json { encodeDefaults = true }

    /** `POST /v1/orders`. */
    fun submit(body: WireSubmit): DeskResult = place(body, gated = true)

    /** A flatten the gateway itself builds (`POST /v1/positions/close`): sent like a submit, never gated. */
    internal fun flatten(body: WireSubmit): DeskResult = place(body, gated = false)

    /**
     * `PATCH /v1/orders/{id}`: [change] applied at the venue and journaled. While a kill scope covers
     * the order's symbol only a pure size reduction passes.
     */
    fun modify(
        clientOrderId: String,
        change: WireChange,
    ): DeskResult =
        locked(clientOrderId) {
            val current =
                journal.order(clientOrderId)?.order
                    ?: return@locked DeskResult.Refused(404, "not_found", "no order $clientOrderId")
            val fields = listOf(change.quantity, change.limitPrice, change.stopPrice)
            if (fields.all { it == null } || fields.any { it != null && positive(it) == null }) {
                return@locked DeskResult.Refused(
                    400,
                    "invalid_request",
                    "a change needs positive decimal fields: $change",
                )
            }
            val parsed = OrderChange(positive(change.quantity), positive(change.limitPrice), positive(change.stopPrice))
            val shrinks = parsed.quantity?.let { it < BigDecimal(current.quantity) } == true
            val pureReduction = shrinks && parsed.limitPrice == null && parsed.stopPrice == null
            if (gate.covers(current.symbol) && !pureReduction) {
                return@locked DeskResult.Refused(423, "kill_switch", "the kill switch covers ${current.symbol}")
            }
            if (!venueUp()) return@locked unavailable("the venue link is down")
            try {
                venue.modify(clientOrderId, parsed)?.let { DeskResult.Ok(appended(it)) }
                    ?: DeskResult.Refused(404, "not_found", "no order $clientOrderId")
            } catch (e: VenueRefusedException) {
                DeskResult.Refused(422, "venue_rejected", e.reason)
            } catch (e: VenueUnavailableException) {
                unavailable(e.message ?: "the venue did not answer")
            }
        }

    private fun place(
        body: WireSubmit,
        gated: Boolean,
    ): DeskResult =
        locked(body.clientOrderId) {
            val hash = hash(body)
            if (journal.isDead(body.clientOrderId)) return@locked conflict("${body.clientOrderId} was written off")
            val record = journal.order(body.clientOrderId)
            if (record != null &&
                record.bodyHash != hash
            ) {
                return@locked conflict("${body.clientOrderId} was sent with another body")
            }
            record?.order?.let { return@locked DeskResult.Ok(it) }
            if (!venueUp()) return@locked unavailable("the venue link is down")
            // A resend of a write-ahead record: what the venue holds of it comes first, ungated (it is placed).
            if (record != null) writeAheads.found(body)?.let { return@locked DeskResult.Ok(it) }
            if (record != null && !writeAheads.settled(body.clientOrderId)) {
                return@locked unavailable("${body.clientOrderId} may still reach the venue; resend to resolve it")
            }
            val order = WireMapping.newOrder(body)
            if (gated) gate.refusal(order)?.let { return@locked DeskResult.Refused(423, "kill_switch", it) }
            if (record == null) journal.writeAhead(body, hash)
            writeAheads.attempted(body.clientOrderId)
            try {
                DeskResult.Ok(appended(venue.place(order)), created = true)
            } catch (e: VenueRefusedException) {
                journal.appendOrder(WireMapping.rejected(body, e.reason, clock()))
                DeskResult.Refused(422, "venue_rejected", e.reason)
            } catch (e: VenueUnavailableException) {
                unavailable(e.message ?: "the venue did not answer")
            } catch (e: RuntimeException) {
                // The order may have reached the venue: answered as retryable, so the resend recovers it.
                unavailable("the place of ${body.clientOrderId} failed (${e.message}); resend to resolve it")
            }
        }

    /**
     * `GET /v1/orders/{id}`: the journal's order, else what the venue holds of it (by label, else its fills);
     * an id neither knows is written off (`404`), unless a place of it may still reach the venue (`503`).
     */
    fun get(clientOrderId: String): DeskResult =
        locked(clientOrderId) {
            journal.order(clientOrderId)?.order?.let { return@locked DeskResult.Ok(it) }
            if (!venueUp()) return@locked unavailable("the venue link is down")
            val pending = journal.pendingSubmit(clientOrderId)
            if (pending != null) writeAheads.found(pending)?.let { return@locked DeskResult.Ok(it) }
            if (pending == null) venue.orderByLabel(clientOrderId)?.let { return@locked DeskResult.Ok(appended(it)) }
            if (!writeAheads.settled(
                    clientOrderId,
                )
            ) {
                return@locked unavailable("$clientOrderId may still reach the venue")
            }
            journal.markDead(clientOrderId)
            DeskResult.Refused(404, "not_found", "no order $clientOrderId")
        }

    /** Resolves write-ahead [body] for the reconciler, under its lock: found, rejected, or left while in flight. */
    internal fun resolve(body: WireSubmit) = locked(body.clientOrderId) { writeAheads.resolve(body) }

    /** `DELETE /v1/orders/{id}`: the order as it ended; cancels pass the kill switch. */
    fun cancel(clientOrderId: String): DeskResult =
        locked(clientOrderId) {
            if (!venueUp()) return@locked unavailable("the venue link is down")
            val ended =
                venue.cancel(clientOrderId)
                    // The venue may have forgotten an order that ended long ago; the journal has not.
                    ?: return@locked journal.order(clientOrderId)?.order?.let { DeskResult.Ok(it) }
                        ?: DeskResult.Refused(404, "not_found", "no order $clientOrderId")
            DeskResult.Ok(appended(ended))
        }

    private fun positive(text: String?): BigDecimal? = text?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    private fun appended(order: com.qkt.venuegateway.adapter.VenueOrder): WireOrder =
        WireMapping.order(order).also { journal.appendOrder(it) }

    private fun hash(body: WireSubmit): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(json.encodeToString(WireSubmit.serializer(), body).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun <T> locked(
        id: String,
        block: () -> T,
    ): T = synchronized(locks.computeIfAbsent(id) { Any() }) { block() }

    private fun conflict(message: String) = DeskResult.Refused(409, "conflict", message)

    private fun unavailable(message: String) = DeskResult.Refused(503, "venue_unavailable", message)
}
