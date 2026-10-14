package com.qkt.venuegateway.deribit.client

import com.qkt.venuegateway.deribit.client.DeribitJson.dec
import com.qkt.venuegateway.deribit.client.DeribitJson.long
import com.qkt.venuegateway.deribit.client.DeribitJson.req
import com.qkt.venuegateway.deribit.client.DeribitJson.text
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Reading Deribit's private answers, every number from its literal text. */
internal object DeribitPrivateJson {
    fun order(o: JsonObject) =
        DeribitOrder(
            orderId = o.need("order_id"),
            label = o.text("label")?.takeIf { it.isNotEmpty() },
            instrument = o.need("instrument_name"),
            direction = o.need("direction"),
            orderType = o.need("order_type"),
            state = o.need("order_state"),
            amount = o.req("amount"),
            filledAmount = o.dec("filled_amount") ?: BigDecimal.ZERO,
            price = o.number("price"),
            averagePrice = o.dec("average_price"),
            triggerPrice = o.dec("trigger_price"),
            triggered = (o["triggered"] as? JsonPrimitive)?.booleanOrNull,
            timeInForce = o.need("time_in_force"),
            reduceOnly = (o["reduce_only"] as? JsonPrimitive)?.booleanOrNull ?: false,
            createdMs = o.long("creation_timestamp"),
            updatedMs = o.long("last_update_timestamp"),
            cancelReason = o.text("cancel_reason"),
            originalOrderType = o.text("original_order_type"),
        )

    fun trade(o: JsonObject) =
        DeribitTrade(
            tradeId = o.need("trade_id"),
            orderId = o.need("order_id"),
            label = o.text("label")?.takeIf { it.isNotEmpty() },
            instrument = o.need("instrument_name"),
            direction = o.need("direction"),
            amount = o.req("amount"),
            price = o.req("price"),
            fee = o.req("fee"),
            feeCurrency = o.need("fee_currency"),
            timestampMs = o.long("timestamp"),
        )

    fun tradePage(o: JsonObject) =
        DeribitPage(
            o["trades"]!!.jsonArray.map { trade(it.jsonObject) },
            (o["has_more"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )

    fun position(o: JsonObject) =
        DeribitPosition(
            instrument = o.need("instrument_name"),
            kind = o.need("kind"),
            direction = o.need("direction"),
            size = o.req("size"),
            sizeCurrency = o.dec("size_currency"),
            averagePrice = o.req("average_price"),
        )

    fun account(o: JsonObject) =
        DeribitAccount(
            currency = o.need("currency"),
            balance = o.req("balance"),
            equity = o.req("equity"),
            initialMargin = o.req("initial_margin"),
            maintenanceMargin = o.req("maintenance_margin"),
            availableFunds = o.req("available_funds"),
        )

    fun transaction(o: JsonObject) =
        DeribitTransaction(
            id = o.long("id"),
            type = o.need("type"),
            instrument = o.text("instrument_name"),
            currency = o.need("currency"),
            interestPl = o.dec("interest_pl"),
            position = o.dec("position"),
            commission = o.dec("commission"),
            timestampMs = o.long("timestamp"),
        )

    /** One page of the transaction log, and the continuation to the next (null on the last). */
    fun transactionPage(o: JsonObject): Pair<List<DeribitTransaction>, Long?> =
        o["logs"]!!.jsonArray.map { transaction(it.jsonObject) } to
            (o["continuation"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    /** One page of the settlement history, newest first, and the continuation to the next (null on the last). */
    fun settlementPage(o: JsonObject): Pair<List<DeribitSettlement>, String?> =
        o["settlements"]!!.jsonArray.map {
            val row = it.jsonObject
            DeribitSettlement(
                row.need("type"),
                row.need("instrument_name"),
                row.req("index_price"),
                row.long("timestamp"),
            )
        } to o.text("continuation")?.takeIf { it != "none" }

    private fun JsonObject.need(key: String): String = text(key) ?: error("deribit field $key missing")

    /** The number at [key], or null when Deribit sent a word or nothing. */
    private fun JsonObject.number(key: String): BigDecimal? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBigDecimalOrNull()
}
