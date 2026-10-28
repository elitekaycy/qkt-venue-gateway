package com.qkt.venuegateway.support

/**
 * A venue's listing as last [read], by [key]: read again once [refreshMs] old, and on a lookup of a key it does
 * not hold once it is at least [missRereadMs] old (venues list new contracts as they go; 0 reads again on every
 * miss). Thread-safe: one read at a time, every caller served the listing it left.
 */
class Listing<T>(
    private val read: () -> List<T>,
    private val key: (T) -> String,
    private val clock: () -> Long,
    private val refreshMs: Long,
    private val missRereadMs: Long = 0,
) {
    private var held: Map<String, T> = emptyMap()
    private var readAtMs: Long? = null

    /** Every listed item, keyed, read again when due. */
    fun all(): Map<String, T> = synchronized(this) { current() }

    /** [key]'s item as listed now, without reading again on a miss. */
    fun cached(key: String): T? = all()[key]

    /** [key]'s item, reading the listing again when it does not hold it and is at least [missRereadMs] old. */
    fun find(key: String): T? =
        synchronized(this) {
            current()[key] ?: if (clock() - readAtMs!! < missRereadMs) null else reread()[key]
        }

    private fun current(): Map<String, T> = readAtMs?.takeIf { clock() - it < refreshMs }?.let { held } ?: reread()

    private fun reread(): Map<String, T> {
        held = read().associateBy(key)
        readAtMs = clock()
        return held
    }
}
