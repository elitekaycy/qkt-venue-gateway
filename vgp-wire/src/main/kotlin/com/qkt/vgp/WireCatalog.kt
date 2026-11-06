package com.qkt.vgp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /v1/instruments` asked with a filter, a page or `updated_since` (wire spec §3): the matching [instruments],
 * by code; with `updated_since`, only those added or changed since, and [removed] the codes no longer listed since.
 * [next] is the `cursor` of the following page, absent on the last; [asOf] is when the gateway read the listing
 * served, the `updated_since` of the client's next poll.
 */
@Serializable
data class WireInstrumentPage(
    val instruments: List<WireInstrument>,
    val removed: List<WireRemoved>? = null,
    val next: String? = null,
    @SerialName("as_of") val asOf: Long,
)

/** A [code] the gateway no longer lists. */
@Serializable
data class WireRemoved(
    val code: String,
    val removed: Boolean = true,
)
