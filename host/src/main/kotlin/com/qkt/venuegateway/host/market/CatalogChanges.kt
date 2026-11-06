package com.qkt.venuegateway.host.market

import com.qkt.vgp.WireInstrument

/**
 * What changed in the served listing, derived by diffing each read with the one before (wire spec §3,
 * `updated_since`): when each code was last added or changed, and each code no longer listed with when it went and
 * how it read last. Removals are kept [removalKeepMs]; a client asking from before that, or from before the first
 * read of this process, cannot be told every removal and reads the listing in full. Thread-safe.
 */
internal class CatalogChanges(
    private val removalKeepMs: Long,
) {
    private var last: Map<String, WireInstrument>? = null
    private var firstReadAt = Long.MAX_VALUE
    private val changedAt = HashMap<String, Long>()
    private val removedAt = HashMap<String, Pair<Long, WireInstrument>>()

    /** Records the listing [listed] read at [at]. */
    @Synchronized
    fun record(
        listed: Map<String, WireInstrument>,
        at: Long,
    ) {
        val before = last ?: emptyMap<String, WireInstrument>().also { firstReadAt = at }
        listed.forEach { (code, instrument) ->
            if (before[code] != instrument) {
                changedAt[code] = at
                removedAt.remove(code)
            }
        }
        before.forEach { (code, instrument) ->
            if (code !in listed) {
                changedAt.remove(code)
                removedAt[code] = at to instrument
            }
        }
        removedAt.values.removeIf { (gone, _) -> gone < at - removalKeepMs }
        last = listed
    }

    /** The earliest `updated_since` answerable at [now]. */
    @Synchronized
    fun horizon(now: Long): Long = maxOf(firstReadAt, now - removalKeepMs)

    /** The codes added or changed at or after [since]. */
    @Synchronized
    fun changedSince(since: Long): Set<String> = changedAt.filterValues { it >= since }.keys.toSet()

    /** Each code removed at or after [since], as it was last listed. */
    @Synchronized
    fun removedSince(since: Long): List<WireInstrument> =
        removedAt.values.filter { (gone, _) -> gone >= since }.map { it.second }
}
