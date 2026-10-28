package com.qkt.venuegateway.support

/** One page of a venue's answer and the cursor of the next, null on the last. */
data class Page<T>(
    val items: List<T>,
    val next: String?,
)

/** One page of a walk by time and whether the venue says it has more after it. */
data class Batch<T>(
    val items: List<T>,
    val more: Boolean,
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

    /**
     * Pages forward by time from [fromMs] while the venue says it has [Batch.more] and the next start is before
     * [endMs]: each page starts at the newest millisecond the last one reached (inclusive, so items sharing it are not
     * skipped; the overlap is dropped by [id]). Only items [keep] admits are held, and the walk stops once [enough] are.
     * A page that cannot move past its start fails naming [what] rather than loop. The first copy of each item, in the
     * order seen.
     */
    fun <T> whileMore(
        fromMs: Long,
        endMs: Long = Long.MAX_VALUE,
        fetch: (startMs: Long) -> Batch<T>,
        id: (T) -> Any,
        time: (T) -> Long,
        what: String,
        keep: (T) -> Boolean = { true },
        enough: Int = Int.MAX_VALUE,
    ): List<T> {
        val held = LinkedHashMap<Any, T>()
        var start = fromMs
        while (start < endMs) {
            val page = fetch(start)
            page.items.filter(keep).forEach { held.putIfAbsent(id(it), it) }
            if (!page.more || page.items.isEmpty() || held.size >= enough) break
            val newest = page.items.maxOf(time)
            check(newest > start) { "$what fill a page within $start ms; they cannot be paged" }
            start = newest
        }
        return held.values.toList()
    }

    /**
     * Walks `[fromMs, toMs]` from its end back, as venues answer the newest of a range first: each page ends just
     * before the oldest item of the last, and the walk stops at an empty page. One item per [time], oldest first.
     */
    fun <T> backwards(
        fromMs: Long,
        toMs: Long,
        fetch: (endMs: Long) -> List<T>,
        time: (T) -> Long,
    ): List<T> {
        val byTime = sortedMapOf<Long, T>()
        var end = toMs
        while (end >= fromMs) {
            val items = fetch(end).filter { time(it) in fromMs..end }
            items.forEach { byTime.putIfAbsent(time(it), it) }
            if (items.isEmpty()) break
            end = items.minOf(time) - 1
        }
        return byTime.values.toList()
    }

    /**
     * Every page of a continuation, in the order answered: it stops at the last page (no continuation), at an empty
     * page, once [stop] says a page reached far enough, or once [maxItems] are held. A continuation answered twice in
     * a row fails rather than loop. Items are returned as answered; de-duplicating and ordering is the caller's.
     */
    fun <T, C : Any> continuation(
        fetch: (continuation: C?) -> Pair<List<T>, C?>,
        stop: (List<T>) -> Boolean = { false },
        maxItems: Int = Int.MAX_VALUE,
    ): List<T> {
        val rows = mutableListOf<T>()
        var continuation: C? = null
        do {
            val (items, next) = fetch(continuation)
            rows += items
            check(next == null || next != continuation) { "the venue's continuation does not advance past $next" }
            continuation = next.takeIf { items.isNotEmpty() && !stop(items) }
        } while (continuation != null && rows.size < maxItems)
        return rows
    }
}
