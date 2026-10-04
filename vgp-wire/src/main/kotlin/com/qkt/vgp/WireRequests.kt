package com.qkt.vgp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `PATCH /v1/orders/{client_order_id}`: the fields to change; absent ones stay as they are. */
@Serializable
data class WireChange(
    val quantity: String? = null,
    @SerialName("limit_price") val limitPrice: String? = null,
    @SerialName("stop_price") val stopPrice: String? = null,
)

/**
 * `POST /v1/positions/close`: a [symbol]'s position, all of it or [quantity]; or one hedging [ticket]. With
 * [clientOrderId] the close is idempotent on it, as a submit is: a retry returns the same closing order.
 */
@Serializable
data class WireClose(
    val symbol: String? = null,
    val quantity: String? = null,
    val ticket: String? = null,
    @SerialName("client_order_id") val clientOrderId: String? = null,
)
