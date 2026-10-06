package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.bybit.client.BybitJson.dec
import com.qkt.venuegateway.bybit.client.BybitJson.long
import com.qkt.venuegateway.bybit.client.BybitJson.need
import com.qkt.venuegateway.bybit.client.BybitJson.req
import com.qkt.venuegateway.bybit.client.BybitJson.text
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bybit's account answers and pushes read into the client's types. Bybit writes "none" as zero in some
 * fields (`triggerPrice` `"0.00"`, `avgPrice` `"0"`) and as `EC_NoError` in `rejectReason`; those read as null.
 */
internal object BybitPrivateJson {
    fun order(o: JsonObject) =
        BybitOrder(
            orderId = o.need("orderId"),
            orderLinkId = o.text("orderLinkId"),
            symbol = o.need("symbol"),
            side = o.need("side"),
            orderType = o.need("orderType"),
            qty = o.req("qty"),
            price = o.dec("price")?.takeIf { it.signum() != 0 },
            triggerPrice = o.dec("triggerPrice")?.takeIf { it.signum() != 0 },
            stopOrderType = o.text("stopOrderType")?.takeIf { it != "UNKNOWN" },
            timeInForce = o.need("timeInForce"),
            orderStatus = o.need("orderStatus"),
            cumExecQty = o.req("cumExecQty"),
            avgPrice = o.dec("avgPrice")?.takeIf { it.signum() != 0 },
            reduceOnly = (o["reduceOnly"] as? JsonPrimitive)?.content == "true",
            rejectReason = o.text("rejectReason")?.takeIf { it != "EC_NoError" },
            positionIdx = o.text("positionIdx")?.toInt() ?: 0,
            createdMs = o.long("createdTime"),
            updatedMs = o.long("updatedTime"),
        )

    fun execution(o: JsonObject) =
        BybitExecution(
            execId = o.need("execId"),
            orderId = o.need("orderId"),
            orderLinkId = o.text("orderLinkId"),
            symbol = o.need("symbol"),
            side = o.need("side"),
            execType = o.text("execType"),
            execQty = o.req("execQty"),
            execPrice = o.req("execPrice"),
            execFee = o.dec("execFee") ?: BigDecimal.ZERO,
            feeCurrency = o.text("feeCurrency"),
            execTimeMs = o.long("execTime"),
            orderQty = o.dec("orderQty"),
            leavesQty = o.dec("leavesQty"),
        )

    fun position(o: JsonObject) =
        BybitPosition(
            symbol = o.need("symbol"),
            side = o.text("side")?.takeIf { it != "None" },
            size = o.req("size"),
            avgPrice = o.dec("avgPrice")?.takeIf { it.signum() != 0 },
            positionIdx = o.text("positionIdx")?.toInt() ?: 0,
            createdMs = o.text("createdTime")?.toLong(),
        )

    /** The wallet answer's account row (a unified account has one) and its row of [coin], zero when it holds none. */
    fun wallet(
        o: JsonObject,
        coin: String,
    ): BybitWallet {
        val row = BybitJson.run { o.items("coin") }.firstOrNull { it.text("coin") == coin }
        return BybitWallet(
            coin = coin,
            walletBalance = row?.dec("walletBalance") ?: BigDecimal.ZERO,
            equity = row?.dec("equity") ?: BigDecimal.ZERO,
            initialMargin = o.req("totalInitialMargin"),
            maintenanceMargin = o.req("totalMaintenanceMargin"),
            available = o.req("totalAvailableBalance"),
        )
    }

    /** [o] as the `/v5/order/create` body. */
    fun create(o: BybitNewOrder): JsonObject =
        buildJsonObject {
            put("category", o.category)
            put("symbol", o.symbol)
            put("side", o.side)
            put("orderType", o.orderType)
            put("qty", o.qty.toPlainString())
            o.price?.let { put("price", it.toPlainString()) }
            o.triggerPrice?.let { put("triggerPrice", it.toPlainString()) }
            o.triggerDirection?.let { put("triggerDirection", it) }
            o.triggerBy?.let { put("triggerBy", it) }
            put("timeInForce", o.timeInForce)
            put("orderLinkId", o.orderLinkId)
            if (o.reduceOnly) put("reduceOnly", true)
            o.positionIdx?.let { put("positionIdx", it) }
            o.orderFilter?.let { put("orderFilter", it) }
            o.marketUnit?.let { put("marketUnit", it) }
        }
}
