package com.qkt.venuegateway.host.market

import com.qkt.vgp.WireInstrument
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlinx.serialization.json.Json

/**
 * The listing the gateway serves (wire spec §3): the venue's live listing, plus every dated contract it
 * listed before and no longer does, until [keepMs] (30 days) after its expiry, an event contract until
 * [eventKeepMs] (7 days: venues list thousands a day), so a client restarting after a weekend still maps a
 * contract it held to its settlement. Dated contracts are remembered as
 * last listed, in memory and in their own SQLite file (written only when one changes); a perpetual or
 * spot instrument is never kept once the venue drops it. Holds for every adapter, whatever its venue
 * offers for expired contracts. Thread-safe.
 */
class InstrumentShelf private constructor(
    private val db: Connection,
    private val clock: () -> Long,
    private val keepMs: Long,
    private val eventKeepMs: Long,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val remembered = HashMap<String, WireInstrument>()

    init {
        db.prepareStatement("SELECT instrument_json FROM instruments").use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    json.decodeFromString(WireInstrument.serializer(), rs.getString(1)).let {
                        remembered[it.code] =
                            it
                    }
                }
            }
        }
    }

    /** The [live] listing and, after it, the remembered contracts it no longer holds, by expiry. */
    @Synchronized
    fun listing(live: List<WireInstrument>): List<WireInstrument> {
        remember(live.filter { it.expiry != null && remembered[it.code] != it })
        forget(clock())
        val liveCodes = live.mapTo(HashSet()) { it.code }
        return live +
            remembered.values.filter { it.code !in liveCodes }.sortedWith(compareBy({ it.expiry }, { it.code }))
    }

    override fun close() = synchronized(this) { db.close() }

    private fun remember(changed: List<WireInstrument>) {
        if (changed.isEmpty()) return
        write("INSERT OR REPLACE INTO instruments(code, expiry, instrument_json) VALUES(?, ?, ?)", changed) { st, i ->
            st.setString(1, i.code)
            st.setLong(2, i.expiry!!)
            st.setString(3, json.encodeToString(WireInstrument.serializer(), i))
        }
        changed.forEach { remembered[it.code] = it }
    }

    private fun forget(now: Long) {
        val expired = remembered.values.filter { it.expiry!! < now - if (it.kind == EVENT) eventKeepMs else keepMs }
        if (expired.isEmpty()) return
        write("DELETE FROM instruments WHERE code = ?", expired) { st, i -> st.setString(1, i.code) }
        expired.forEach { remembered.remove(it.code) }
    }

    private fun write(
        sql: String,
        rows: List<WireInstrument>,
        bind: (PreparedStatement, WireInstrument) -> Unit,
    ) {
        db.autoCommit = false
        try {
            db.prepareStatement(sql).use { st ->
                rows.forEach {
                    bind(st, it)
                    st.addBatch()
                }
                st.executeBatch()
            }
            db.commit()
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val EVENT = "event"

        /** Opens (or creates) the shelf at [file], keeping expired contracts [keepMs] and events [eventKeepMs]. */
        fun open(
            file: Path,
            keepMs: Long = 30 * DAY_MS,
            eventKeepMs: Long = 7 * DAY_MS,
            clock: () -> Long,
        ): InstrumentShelf {
            file.parent?.let { Files.createDirectories(it) }
            val db = DriverManager.getConnection("jdbc:sqlite:$file")
            db.createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute(
                    "CREATE TABLE IF NOT EXISTS instruments(code TEXT PRIMARY KEY, expiry INTEGER NOT NULL, instrument_json TEXT NOT NULL)",
                )
            }
            return InstrumentShelf(db, clock, keepMs, eventKeepMs)
        }
    }
}
