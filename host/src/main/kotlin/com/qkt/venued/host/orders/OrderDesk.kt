package com.qkt.venued.host.orders

import com.qkt.venued.adapter.VenueAdapter
import com.qkt.venued.adapter.VenueRefusedException
import com.qkt.venued.adapter.VenueUnavailableException
import com.qkt.venued.host.journal.Journal
import com.qkt.venued.host.wire.WireMapping
import com.qkt.vgp.WireOrder
import com.qkt.vgp.WireSubmit
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
 * resolved by the venue's label on the resend, never by placing again. While the venue is down
 * ([venueUp] false) a submit is `503` before anything is written; the kill switch gates every submit.
 * Each id is handled under its own lock.
 */
class OrderDesk(
    private val journal: Journal,
    private val venue: VenueAdapter,
    private val venueUp: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val gate = KillSwitchGate(journal, venue)
    private val locks = ConcurrentHashMap<String, Any>()
    private val json = Json { encodeDefaults = true }

    /** `POST /v1/orders`. */
    fun submit(body: WireSubmit): DeskResult =
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
            val order = WireMapping.newOrder(body)
            gate.refusal(order)?.let { return@locked DeskResult.Refused(423, "kill_switch", it) }
            if (!venueUp()) return@locked unavailable("the venue link is down")
            if (record == null) {
                journal.writeAhead(body, hash)
            } else {
                venue.orderByLabel(body.clientOrderId)?.let { return@locked DeskResult.Ok(appended(it)) }
            }
            try {
                DeskResult.Ok(appended(venue.place(order)), created = true)
            } catch (e: VenueRefusedException) {
                journal.appendOrder(WireMapping.rejected(body, e.reason, clock()))
                DeskResult.Refused(422, "venue_rejected", e.reason)
            } catch (e: VenueUnavailableException) {
                unavailable(e.message ?: "the venue did not answer")
            }
        }

    /** `GET /v1/orders/{id}`: the journal's order, else the venue's by label; an id neither knows is written off (`404`). */
    fun get(clientOrderId: String): DeskResult =
        locked(clientOrderId) {
            journal.order(clientOrderId)?.order?.let { return@locked DeskResult.Ok(it) }
            if (!venueUp()) return@locked unavailable("the venue link is down")
            venue.orderByLabel(clientOrderId)?.let { return@locked DeskResult.Ok(appended(it)) }
            journal.markDead(clientOrderId)
            DeskResult.Refused(404, "not_found", "no order $clientOrderId")
        }

    /** `DELETE /v1/orders/{id}`: the order as it ended; cancels pass the kill switch. */
    fun cancel(clientOrderId: String): DeskResult =
        locked(clientOrderId) {
            if (!venueUp()) return@locked unavailable("the venue link is down")
            val ended =
                venue.cancel(clientOrderId)
                    ?: return@locked DeskResult.Refused(404, "not_found", "no order $clientOrderId")
            DeskResult.Ok(appended(ended))
        }

    private fun appended(order: com.qkt.venued.adapter.VenueOrder): WireOrder =
        WireMapping.order(order).also { wire ->
            if (journal.order(wire.clientOrderId)?.order !=
                wire
            ) {
                journal.appendOrder(wire)
            }
        }

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
