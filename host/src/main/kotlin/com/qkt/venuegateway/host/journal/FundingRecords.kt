package com.qkt.venuegateway.host.journal

import com.qkt.vgp.WireFunding
import java.sql.Connection
import kotlinx.serialization.json.Json

/** The SQL behind the journal's funding records, run under the journal's lock. */
internal class FundingRecords(
    private val db: Connection,
    private val json: Json,
) {
    /** Inserts [f] unless its id is already there; true when inserted. */
    fun insert(f: WireFunding): Boolean =
        db.prepareStatement(INSERT).use {
            it.setString(1, f.fundingId)
            it.setLong(2, f.time)
            it.setString(3, json.encodeToString(WireFunding.serializer(), f))
            it.executeUpdate() == 1
        }

    /** The records from [from] to [to], oldest first. */
    fun between(
        from: Long,
        to: Long,
    ): List<WireFunding> =
        db.prepareStatement(BETWEEN).use {
            it.setLong(1, from)
            it.setLong(2, to)
            it.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(json.decodeFromString(WireFunding.serializer(), rs.getString(1)))
                    }
                }
            }
        }

    private companion object {
        const val INSERT = "INSERT OR IGNORE INTO funding(funding_id, time, funding_json) VALUES(?, ?, ?)"
        const val BETWEEN = "SELECT funding_json FROM funding WHERE time >= ? AND time <= ? ORDER BY time, funding_id"
    }
}
