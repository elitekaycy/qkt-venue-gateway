package com.qkt.venued.host.journal

import java.sql.Connection

/**
 * The journal's tables. WAL keeps readers off the writer's way; `synchronous=FULL` makes every committed
 * event survive a power loss, as a money journal must.
 */
internal object JournalSchema {
    private val tables =
        listOf(
            "meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "events(seq INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, time INTEGER, data TEXT)",
            "orders(client_order_id TEXT PRIMARY KEY, body_hash TEXT, body_json TEXT, status TEXT NOT NULL, order_json TEXT)",
            "fills(fill_id TEXT PRIMARY KEY, client_order_id TEXT NOT NULL, time INTEGER NOT NULL, fill_json TEXT NOT NULL)",
            "settlements(symbol TEXT NOT NULL, time INTEGER NOT NULL, settlement_json TEXT NOT NULL, PRIMARY KEY(symbol, time))",
            "dead_ids(client_order_id TEXT PRIMARY KEY)",
        )

    /** Creates any missing table in [db]. */
    fun create(db: Connection) =
        db.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=FULL")
            tables.forEach { st.execute("CREATE TABLE IF NOT EXISTS $it") }
        }
}
