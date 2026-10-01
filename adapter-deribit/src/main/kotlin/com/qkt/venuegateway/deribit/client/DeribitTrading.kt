package com.qkt.venuegateway.deribit.client

import java.math.BigDecimal

/** An order to send, in Deribit's words: [type] `limit`, `market`, `stop_market` or `stop_limit`. */
data class DeribitNewOrder(
    val label: String,
    val instrument: String,
    val direction: String,
    val type: String,
    val amount: BigDecimal,
    val price: BigDecimal?,
    val triggerPrice: BigDecimal?,
    val trigger: String?,
    val timeInForce: String,
    val reduceOnly: Boolean,
)

/**
 * One Deribit account's private API, as adapters use it; [DeribitPrivateClient] is the real one. Every
 * call is scoped to one settlement currency; a venue refusal is [DeribitException], the venue being
 * unreachable an [java.io.IOException].
 */
interface DeribitTrading : AutoCloseable {
    /** Opens the link; order and trade pushes reach the handlers given at construction from then on. */
    fun start()

    fun account(currency: String): DeribitAccount

    fun positions(currency: String): List<DeribitPosition>

    fun openOrders(currency: String): List<DeribitOrder>

    /** Every order carrying [label] that Deribit still reports (working ones and recent closed ones). */
    fun ordersByLabel(
        currency: String,
        label: String,
    ): List<DeribitOrder>

    fun place(order: DeribitNewOrder): DeribitOrder

    /** Cancels the working orders labelled [label]; returns how many were cancelled. */
    fun cancelByLabel(
        currency: String,
        label: String,
    ): Int

    /** Changes the working order labelled [label]; a null price or trigger stays as it is. */
    fun editByLabel(
        label: String,
        instrument: String,
        amount: BigDecimal,
        price: BigDecimal?,
        triggerPrice: BigDecimal?,
    ): DeribitOrder

    /** The account's executions from [fromMs] to [toMs], oldest first, across every page. */
    fun trades(
        currency: String,
        fromMs: Long,
        toMs: Long,
    ): List<DeribitTrade>
}
