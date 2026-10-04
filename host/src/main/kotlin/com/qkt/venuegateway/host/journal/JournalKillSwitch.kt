package com.qkt.venuegateway.host.journal

import com.qkt.vgp.WireKillSwitch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

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
 */
fun Journal.updateKillSwitch(change: (WireKillSwitch) -> WireKillSwitch): WireKillSwitch =
    transaction { change(killSwitch()).also { write(it) } }

private fun Journal.write(scope: WireKillSwitch) {
    setMeta("kill_all", scope.all.toString())
    setMeta("kill_symbols", json.encodeToString(ListSerializer(String.serializer()), scope.symbols))
}
