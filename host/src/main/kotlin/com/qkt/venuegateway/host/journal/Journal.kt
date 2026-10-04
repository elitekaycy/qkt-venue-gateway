package com.qkt.venuegateway.host.journal

import com.qkt.vgp.WireEvent
import com.qkt.vgp.WireFill
import com.qkt.vgp.WireOrder
import com.qkt.vgp.WirePosition
import com.qkt.vgp.WireSettlement
import com.qkt.vgp.WireSubmit
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * The gateway's memory, one SQLite file per account: the event log the stream replays (`seq` rises by
 * exactly one per event within [stream]), every order the client sent with its body hash (idempotent
 * submits), every fill, settlement and funding record once (by venue fill id; by symbol and time; by
 * funding id), the ids written off as dead, and the kill switch ([killSwitch], [appendFunding]). Each event is appended in the same transaction as its de-duplication
 * row. A new file starts a new [stream], which tells every client to resynchronize. Thread-safe: one
 * connection, used under this object's lock.
 */
class Journal private constructor(
    private val db: Connection,
) : AutoCloseable {
    internal val json = Json { ignoreUnknownKeys = true }
    internal val records = JournalRecords(db, json)
    internal val fundingRecords = FundingRecords(db, json)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Long) -> Unit>()

    /** Calls [listener] with the latest sequence number after every committed event, outside the lock. */
    fun onAppend(listener: (Long) -> Unit) {
        listeners += listener
    }

    private val opened: String? = synchronized(this) { meta("stream") }

    /**
     * Whether this process created the journal (a first start, or a lost file): orders a client sent before
     * may then be at the venue with no record here, so a submit with no record is looked up there first.
     */
    val createdThisRun: Boolean = opened == null

    /** The event log's identity; a new journal file has a new one. */
    val stream: String =
        opened ?: synchronized(this) { UUID.randomUUID().toString().also { setMeta("stream", it) } }

    /** The latest event's sequence number; 0 before any. */
    fun latestSeq(): Long = synchronized(this) { records.long("SELECT COALESCE(MAX(seq), 0) FROM events") }

    /** Up to [limit] events after [seq], oldest first. */
    fun eventsAfter(
        seq: Long,
        limit: Int = 1000,
    ): List<WireEvent> = synchronized(this) { records.events(stream, seq, limit) }

    /** The oldest retained event's sequence number, or null when the log is empty. */
    fun oldestSeq(): Long? = synchronized(this) { records.long("SELECT MIN(seq) FROM events").takeIf { it > 0 } }

    /**
     * Records [order]'s state and appends its `order` event, unless the journal already holds that state
     * or a later one; true when appended. The check and the write are one step, so the same state reported
     * at once by the order desk and by a venue push is journaled once, and an older state arriving after a
     * newer one (a place answer overtaken by its fill push, a reconciler snapshot overtaken by a fill) is
     * dropped: an order never goes back from final to working, nor does its filled quantity shrink.
     */
    fun appendOrder(order: WireOrder): Boolean =
        appending {
            val held = records.order(order.clientOrderId)?.order
            if (held == order || (held != null && OrderProgress.regresses(held, order))) {
                false
            } else {
                records.upsertOrder(order)
                records.event("order", order.updatedAt, json.encodeToJsonElement(WireOrder.serializer(), order))
                true
            }
        }

    /** Records [order]'s state without an event (a write-ahead record resolved quietly). */
    fun updateOrder(order: WireOrder) = transaction { records.upsertOrder(order) }

    /** Appends [fill] and its event unless its fill id was journaled before; true when new. */
    fun appendFill(fill: WireFill): Boolean =
        appending {
            records.insertFill(fill) &&
                records.event("fill", fill.time, json.encodeToJsonElement(WireFill.serializer(), fill)).let { true }
        }

    /** Appends a `position` event: [position] as the venue holds it after a change, at [time]. */
    fun appendPosition(
        position: WirePosition,
        time: Long,
    ): Boolean =
        appending {
            records.event("position", time, json.encodeToJsonElement(WirePosition.serializer(), position)).let { true }
        }

    /** Appends [settlement] and its event unless the same symbol and time were journaled before; true when new. */
    fun appendSettlement(settlement: WireSettlement): Boolean =
        appending {
            records.insertSettlement(settlement) &&
                records
                    .event(
                        "settlement",
                        settlement.time,
                        json.encodeToJsonElement(WireSettlement.serializer(), settlement),
                    ).let { true }
        }

    /** The write-ahead record of a new submit: its [body] and [bodyHash], before the venue sees it. */
    fun writeAhead(
        body: WireSubmit,
        bodyHash: String,
    ) = transaction { records.writeAhead(body, bodyHash) }

    fun order(clientOrderId: String): OrderRecord? = synchronized(this) { records.order(clientOrderId) }

    /** Orders the venue holds as working, as last journaled. */
    fun workingOrders(): List<WireOrder> = synchronized(this) { records.ordersWithStatus("working") }

    /** The submit of [clientOrderId] while it is only a write-ahead record (no venue answer yet), else null. */
    fun pendingSubmit(clientOrderId: String): WireSubmit? = synchronized(this) { records.pendingBody(clientOrderId) }

    /** The submits of write-ahead records the venue's answer never reached (a crash in between). */
    fun unresolved(): List<WireSubmit> = synchronized(this) { records.pendingBodies() }

    fun fills(
        fromMs: Long,
        toMs: Long,
    ): List<WireFill> = synchronized(this) { records.fills(fromMs, toMs) }

    fun fillsOf(clientOrderId: String): List<WireFill> = synchronized(this) { records.fillsOf(clientOrderId) }

    fun settlements(
        fromMs: Long,
        toMs: Long,
    ): List<WireSettlement> = synchronized(this) { records.settlements(fromMs, toMs) }

    fun settlementsOf(symbol: String): List<WireSettlement> = synchronized(this) { records.settlementsOf(symbol) }

    fun isDead(clientOrderId: String): Boolean = synchronized(this) { records.isDead(clientOrderId) }

    /** Writes [clientOrderId] off: no order may ever be placed under it. */
    fun markDead(clientOrderId: String) = transaction { records.markDead(clientOrderId) }

    override fun close() = synchronized(this) { db.close() }

    /** A transaction that may append an event: listeners hear the new sequence number once it is committed. */
    internal fun appending(block: () -> Boolean): Boolean {
        val appended = transaction(block)
        if (appended) {
            val seq = latestSeq()
            listeners.forEach { it(seq) }
        }
        return appended
    }

    internal fun <T> transaction(block: () -> T): T =
        synchronized(this) {
            db.autoCommit = false
            try {
                block().also { db.commit() }
            } catch (e: Throwable) {
                db.rollback()
                throw e
            } finally {
                db.autoCommit = true
            }
        }

    internal fun meta(key: String): String? = records.meta(key)

    internal fun setMeta(
        key: String,
        value: String,
    ) = records.setMeta(key, value)

    companion object {
        /** Opens (or creates) the journal at [file]. */
        fun open(file: Path): Journal {
            file.parent?.let { Files.createDirectories(it) }
            val db = DriverManager.getConnection("jdbc:sqlite:$file")
            JournalSchema.create(db)
            return Journal(db)
        }
    }
}
