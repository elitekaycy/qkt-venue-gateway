package com.qkt.venuegateway.host.journal

import com.qkt.vgp.WireKillSwitch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The kill switch as the journal last recorded it. */
fun Journal.killSwitch(): WireKillSwitch =
    synchronized(this) {
        val symbols = meta("kill_symbols")?.let { json.decodeFromString(ListSerializer(String.serializer()), it) }
        WireKillSwitch(meta("kill_all") == "true", symbols.orEmpty())
    }

/** Records [scope] as the kill switch. */
fun Journal.setKillSwitch(scope: WireKillSwitch) = transaction { write(scope) }

/**
 * Applies [change] to the kill switch as it stands and records the result, in one transaction, so two
 * guardians changing different scopes at once both take effect; returns the switch as it now stands.
 * A change that moves the switch appends a `kill` event at [time] carrying it, in the same transaction,
 * so the stream tells every client when the switch flipped; a change that leaves it as it was appends none.
 */
fun Journal.updateKillSwitch(
    time: Long,
    change: (WireKillSwitch) -> WireKillSwitch,
): WireKillSwitch {
    var now: WireKillSwitch? = null
    appending {
        val before = killSwitch()
        val after = change(before).also { now = it }
        if (after == before) return@appending false
        write(after)
        records.event("kill", time, whole.encodeToJsonElement(WireKillSwitch.serializer(), after))
        true
    }
    return checkNotNull(now)
}

private fun Journal.write(scope: WireKillSwitch) {
    setMeta("kill_all", scope.all.toString())
    setMeta("kill_symbols", json.encodeToString(ListSerializer(String.serializer()), scope.symbols))
}

/** Encodes the switch whole, `symbols` included when empty, as `/v1/health` reports it. */
private val whole = Json { encodeDefaults = true }
