package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.host.wire.InvalidRequestException
import com.qkt.vgp.WireInstrument
import com.qkt.vgp.WireInstrumentPage
import com.qkt.vgp.WireRemoved

/**
 * The optional parameters of `GET /v1/instruments` (wire spec §3): filters on [kind], [category], [series], [event]
 * and [status] (`open`: the venue lists it and it can be opened now; `closed`: anything else), [updatedSince] for
 * changes only, and a page of at most [limit] codes after [cursor]. A removal is filtered as it was last listed,
 * whatever its status.
 */
internal class CatalogQuery(
    val kind: String? = null,
    val category: String? = null,
    val series: String? = null,
    val event: String? = null,
    val status: String? = null,
    val updatedSince: Long? = null,
    val cursor: String? = null,
    val limit: Int? = null,
) {
    /** The page of [read] this query asks for, at [now]; [changes] knows what changed since `updated_since`. */
    fun page(
        read: Catalog.Read,
        changes: CatalogChanges,
        now: Long,
    ): WireInstrumentPage {
        val since = updatedSince
        val listed =
            if (since == null) {
                read.listing
            } else {
                val horizon = changes.horizon(now)
                if (since < horizon) {
                    throw InvalidRequestException(
                        "updated_since $since is before $horizon, the oldest change the gateway knows: read in full",
                    )
                }
                changes.changedSince(since).mapNotNull(read.byCode::get)
            }
        val removed = if (since == null) emptyList() else changes.removedSince(since)
        val gone = removed.filter(::matches).map { Row(it.code, null) }
        val rows =
            listed
                .filter { matches(it) && statusOf(it, read, now) }
                .map { Row(it.code, it) }
                .plus(gone)
                .sortedBy { it.code }
                .filter { cursor == null || it.code > cursor }
        val page = limit?.let { rows.take(it) } ?: rows
        return WireInstrumentPage(
            instruments = page.mapNotNull { it.instrument },
            removed = since?.let { page.filter { it.instrument == null }.map { WireRemoved(it.code) } },
            next = page.lastOrNull()?.code?.takeIf { page.size < rows.size },
            asOf = read.readAt,
        )
    }

    /** One code of a page: listed with its [instrument], or removed (null). */
    private class Row(
        val code: String,
        val instrument: WireInstrument?,
    )

    private fun matches(i: WireInstrument) =
        (kind == null || i.kind == kind) &&
            (category == null || i.category == category) &&
            (series == null || i.series == series) &&
            (event == null || i.event == event)

    private fun statusOf(
        i: WireInstrument,
        read: Catalog.Read,
        now: Long,
    ): Boolean {
        val open =
            i.code in read.live &&
                i.trading !in SHUT &&
                (i.closeAt ?: Long.MAX_VALUE) > now &&
                (i.expiry ?: Long.MAX_VALUE) > now
        return status == null || status == ALL || (status == OPEN) == open
    }

    companion object {
        private const val OPEN = "open"
        private const val ALL = "all"
        private val SHUT = setOf("close_only", "disabled")
        private val STATUSES = setOf(OPEN, "closed", ALL)
        private val NAMES = listOf("kind", "category", "series", "event", "status", "updated_since", "cursor", "limit")

        /** The query [param] carries, null when it carries none of its parameters; a bad value is refused by name. */
        fun of(param: (String) -> String?): CatalogQuery? {
            if (NAMES.none { param(it) != null }) return null
            val status = param("status")
            if (status != null && status !in STATUSES) {
                throw InvalidRequestException("status must be open, closed or all: $status")
            }
            val since = param("updated_since")
            val limit = param("limit")
            return CatalogQuery(
                kind = param("kind"),
                category = param("category"),
                series = param("series"),
                event = param("event"),
                status = status,
                updatedSince =
                    since?.let { it.toLongOrNull() ?: throw InvalidRequestException("updated_since must be epoch ms") },
                cursor = param("cursor"),
                limit =
                    limit?.let { raw ->
                        raw.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
                            ?: throw InvalidRequestException("limit must be 1 to $MAX_LIMIT: $raw")
                    },
            )
        }

        /** The most instruments one page holds. */
        const val MAX_LIMIT = 5000
    }
}
