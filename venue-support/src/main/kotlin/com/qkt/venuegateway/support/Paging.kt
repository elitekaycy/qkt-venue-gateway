package com.qkt.venuegateway.support

/** One page of a venue's answer and the cursor of the next, null on the last. */
data class Page<T>(
    val items: List<T>,
    val next: String?,
)

/**
 * The loops every venue's history needs, written once. Each returns its items oldest first by [time] with one copy
 * per [id], and fails rather than loop when a venue answers the same page twice.
 */
object Paging {
    /** [fetch] over `[fromMs, toMs)` cut into spans of at most [spanMs], as venues cap a query's range. */
    fun <T> spans(
        fromMs: Long,
        toMs: Long,
        spanMs: Long,
        fetch: (fromMs: Long, toMs: Long) -> List<T>,
        id: (T) -> Any,
        time: (T) -> Long,
    ): List<T> {
        require(spanMs > 0) { "span must be positive: $spanMs" }
        val out = LinkedHashMap<Any, T>()
        var from = fromMs
        while (from < toMs) {
            val to = minOf(toMs, from + spanMs)
            fetch(from, to).forEach { out.putIfAbsent(id(it), it) }
            from = to
        }
        return out.values.sortedBy(time)
    }

    /** Every page from [first] on, following each page's cursor; a cursor answered twice fails. */
    fun <T> cursor(
        first: String? = null,
        fetch: (cursor: String?) -> Page<T>,
        id: (T) -> Any,
        time: (T) -> Long,
    ): List<T> {
        val out = LinkedHashMap<Any, T>()
        val seen = HashSet<String>()
        var cursor = first
        while (true) {
            val page = fetch(cursor)
            page.items.forEach { out.putIfAbsent(id(it), it) }
            val next = page.next ?: break
            check(seen.add(next)) { "the venue answered cursor $next twice" }
            cursor = next
        }
        return out.values.sortedBy(time)
    }

    /**
     * Pages forward by time from [fromMs] to [toMs]: [fetch] answers at most [limit] items from a time on, oldest
     * first, and the next page starts at the newest time seen (inclusive, as several items can share a millisecond;
     * the overlap is de-duplicated by [id]). A full page that does not move past its start fails.
     */
    fun <T> byTime(
        fromMs: Long,
        toMs: Long,
        limit: Int,
        fetch: (fromMs: Long) -> List<T>,
        id: (T) -> Any,
        time: (T) -> Long,
    ): List<T> {
        val out = LinkedHashMap<Any, T>()
        var from = fromMs
        while (from < toMs) {
            val page = fetch(from).filter { time(it) < toMs }
            page.forEach { out.putIfAbsent(id(it), it) }
            if (page.size < limit) break
            val newest = page.maxOf(time)
            check(newest > from) { "a full page of $limit items at $from does not move forward" }
            from = newest
        }
        return out.values.sortedBy(time)
    }
}
