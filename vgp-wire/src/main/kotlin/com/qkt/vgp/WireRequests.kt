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

/** `POST /v1/positions/close`: a [symbol]'s position, all of it or [quantity]; or one hedging [ticket]. */
@Serializable
data class WireClose(
    val symbol: String? = null,
    val quantity: String? = null,
    val ticket: String? = null,
)
