package com.qkt.venuegateway.host.journal

import com.qkt.vgp.WireEvent
import com.qkt.vgp.WireFill
import com.qkt.vgp.WireOrder
import com.qkt.vgp.WireSettlement
import com.qkt.vgp.WireSubmit
import java.sql.Connection
import java.sql.ResultSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The SQL behind [Journal]: one statement per question, run by the journal under its lock. */
internal class JournalRecords(
    private val db: Connection,
    private val json: Json,
) {
    fun long(sql: String): Long =
        db.createStatement().use { it.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0 } }

    fun meta(key: String): String? =
        query("SELECT value FROM meta WHERE key = ?", key) { it.getString(1) }.firstOrNull()

    fun setMeta(
        key: String,
        value: String,
    ) = update(
        "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        key,
        value,
    )

    fun event(
        type: String,
        time: Long?,
        data: JsonElement,
    ) = update("INSERT INTO events(type, time, data) VALUES(?, ?, ?)", type, time, data.toString())

    fun events(
        stream: String,
        after: Long,
        limit: Int,
    ): List<WireEvent> =
        query("SELECT seq, type, time, data FROM events WHERE seq > ? ORDER BY seq LIMIT ?", after, limit) { rs ->
            val time = rs.getLong(3).takeUnless { rs.wasNull() }
            WireEvent(stream, rs.getLong(1), rs.getString(2), time, json.parseToJsonElement(rs.getString(4)))
        }

    fun writeAhead(
        body: WireSubmit,
        hash: String,
    ) = update(
        "INSERT INTO orders(client_order_id, body_hash, body_json, status) VALUES(?, ?, ?, 'pending')",
        body.clientOrderId,
        hash,
        json.encodeToString(WireSubmit.serializer(), body),
    )

    fun upsertOrder(order: WireOrder) =
        update(
            "INSERT INTO orders(client_order_id, status, order_json) VALUES(?, ?, ?) ON CONFLICT(client_order_id) " +
                "DO UPDATE SET status = excluded.status, order_json = excluded.order_json",
            order.clientOrderId,
            order.status,
            json.encodeToString(WireOrder.serializer(), order),
        )

    fun order(id: String): OrderRecord? =
        query("SELECT client_order_id, body_hash, order_json FROM orders WHERE client_order_id = ?", id) { rs ->
            OrderRecord(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3)?.let {
                    json.decodeFromString(WireOrder.serializer(), it)
                },
            )
        }.firstOrNull()

    fun ordersWithStatus(status: String): List<WireOrder> =
        query("SELECT order_json FROM orders WHERE status = ? ORDER BY client_order_id", status) {
            json.decodeFromString(WireOrder.serializer(), it.getString(1))
        }

    fun pendingBodies(): List<WireSubmit> =
        query("SELECT body_json FROM orders WHERE status = 'pending' ORDER BY client_order_id") {
            json.decodeFromString(WireSubmit.serializer(), it.getString(1))
        }

    fun pendingBody(id: String): WireSubmit? =
        query("SELECT body_json FROM orders WHERE client_order_id = ? AND status = 'pending'", id) {
            json.decodeFromString(WireSubmit.serializer(), it.getString(1))
        }.firstOrNull()

    fun insertFill(fill: WireFill): Boolean =
        update(
            "INSERT OR IGNORE INTO fills(fill_id, client_order_id, time, fill_json) VALUES(?, ?, ?, ?)",
            fill.fillId,
            fill.clientOrderId,
            fill.time,
            json.encodeToString(WireFill.serializer(), fill),
        ) == 1

    fun fills(
        from: Long,
        to: Long,
    ): List<WireFill> =
        query(
            "SELECT fill_json FROM fills WHERE time >= ? AND time <= ? ORDER BY time, fill_id",
            from,
            to,
            read = ::fill,
        )

    fun fillsOf(id: String): List<WireFill> =
        query("SELECT fill_json FROM fills WHERE client_order_id = ? ORDER BY time, fill_id", id, read = ::fill)

    fun insertSettlement(s: WireSettlement): Boolean =
        update(
            "INSERT OR IGNORE INTO settlements(symbol, time, settlement_json) VALUES(?, ?, ?)",
            s.symbol,
            s.time,
            json.encodeToString(WireSettlement.serializer(), s),
        ) == 1

    fun settlements(
        from: Long,
        to: Long,
    ): List<WireSettlement> =
        query(
            "SELECT settlement_json FROM settlements WHERE time >= ? AND time <= ? ORDER BY time, symbol",
            from,
            to,
            read = ::settlement,
        )

    fun settlementsOf(symbol: String): List<WireSettlement> =
        query("SELECT settlement_json FROM settlements WHERE symbol = ? ORDER BY time", symbol, read = ::settlement)

    fun isDead(id: String): Boolean =
        query("SELECT 1 FROM dead_ids WHERE client_order_id = ?", id) { true }.isNotEmpty()

    fun markDead(id: String) = update("INSERT OR IGNORE INTO dead_ids(client_order_id) VALUES(?)", id)

    private fun fill(rs: ResultSet) = json.decodeFromString(WireFill.serializer(), rs.getString(1))

    private fun settlement(rs: ResultSet) = json.decodeFromString(WireSettlement.serializer(), rs.getString(1))

    private fun update(
        sql: String,
        vararg args: Any?,
    ): Int =
        db.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }

    private fun <T> query(
        sql: String,
        vararg args: Any?,
        read: (ResultSet) -> T,
    ): List<T> =
        db.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }
}

/** One order the client sent: its [bodyHash] (null when it reached the journal another way) and last known [order]. */
data class OrderRecord(
    val clientOrderId: String,
    val bodyHash: String?,
    val order: WireOrder?,
)
