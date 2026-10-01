package com.qkt.venuegateway.host.journal

import com.qkt.vgp.WireEvent
import com.qkt.vgp.WireFill
import com.qkt.vgp.WireKillSwitch
import com.qkt.vgp.WireOrder
import com.qkt.vgp.WirePosition
import com.qkt.vgp.WireSettlement
import com.qkt.vgp.WireSubmit
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The gateway's memory, one SQLite file per account: the event log the stream replays (`seq` rises by
 * exactly one per event within [stream]), every order the client sent with its body hash (idempotent
 * submits), every fill and settlement once (by venue fill id; by symbol and time), the ids written off
 * as dead, and the kill switch. Each event is appended in the same transaction as its de-duplication
 * row. A new file starts a new [stream], which tells every client to resynchronize. Thread-safe: one
 * connection, used under this object's lock.
 */
class Journal private constructor(
    private val db: Connection,
) : AutoCloseable {
    internal val json = Json { ignoreUnknownKeys = true }
    private val records = JournalRecords(db, json)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Long) -> Unit>()

    /** Calls [listener] with the latest sequence number after every committed event, outside the lock. */
    fun onAppend(listener: (Long) -> Unit) {
        listeners += listener
    }

    /** The event log's identity; a new journal file has a new one. */
    val stream: String =
        synchronized(this) {
            meta("stream")
                ?: UUID.randomUUID().toString().also { setMeta("stream", it) }
        }

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
     * Records [order]'s state and appends its `order` event, unless the journal already holds exactly
     * that state; true when appended. The check and the write are one step, so the same state reported
     * at once by the order desk and by a venue push is journaled once.
     */
    fun appendOrder(order: WireOrder): Boolean =
        appending {
            if (records.order(order.clientOrderId)?.order == order) {
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

    /** The submits of write-ahead records the venue's answer never reached (a crash in between). */
    fun unresolved(): List<WireSubmit> = synchronized(this) { records.pendingBodies() }

    /** The newest journaled fill's time; 0 before any. */
    fun latestFillTime(): Long = synchronized(this) { records.latestTime("fills") }

    /** The newest journaled settlement's time; 0 before any. */
    fun latestSettlementTime(): Long = synchronized(this) { records.latestTime("settlements") }

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

    fun killSwitch(): WireKillSwitch =
        synchronized(this) {
            val symbols = meta("kill_symbols")?.let { json.decodeFromString(ListSerializer(String.serializer()), it) }
            WireKillSwitch(meta("kill_all") == "true", symbols.orEmpty())
        }

    fun setKillSwitch(scope: WireKillSwitch) =
        transaction {
            setMeta("kill_all", scope.all.toString())
            setMeta("kill_symbols", json.encodeToString(ListSerializer(String.serializer()), scope.symbols))
        }

    override fun close() = synchronized(this) { db.close() }

    /** A transaction that may append an event: listeners hear the new sequence number once it is committed. */
    private fun appending(block: () -> Boolean): Boolean {
        val appended = transaction(block)
        if (appended) {
            val seq = latestSeq()
            listeners.forEach { it(seq) }
        }
        return appended
    }

    private fun <T> transaction(block: () -> T): T =
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

    private fun meta(key: String): String? = records.meta(key)

    private fun setMeta(
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
