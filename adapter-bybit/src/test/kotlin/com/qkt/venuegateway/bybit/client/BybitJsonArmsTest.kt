package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.bybit.client.BybitJson.cursor
import com.qkt.venuegateway.bybit.client.BybitJson.items
import com.qkt.venuegateway.bybit.firstRecord
import com.qkt.venuegateway.bybit.nulled
import com.qkt.venuegateway.bybit.with
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitJsonArmsTest {
    private val empty = JsonObject(emptyMap())

    @Test
    fun `a result with no list, or rows that are no array, are refused by name`() {
        assertThatThrownBy { empty.items() }.hasMessageContaining("field list missing")
        assertThatThrownBy { empty.items("coin") }.hasMessageContaining("field coin missing")
        assertThatThrownBy { BybitJson.rows(null) }.hasMessageContaining("rows missing")
        assertThatThrownBy { BybitJson.rows(JsonPrimitive("x")) }.hasMessageContaining("rows missing")
    }

    @Test
    fun `rows are their strings, and a page cursor of null text or no text is the last page`() {
        val rows = BybitJson.rows(JsonArray(listOf(JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive(2))))))

        assertThat(rows).containsExactly(listOf("1", "2"))
        assertThat(empty.cursor()).isNull()
        assertThat(empty.with("nextPageCursor" to "").cursor()).isNull()
        assertThat(empty.with("nextPageCursor" to "null").cursor()).isNull()
        assertThat(empty.nulled("nextPageCursor").cursor()).isNull()
        assertThat(empty.with("nextPageCursor" to "a%3Db").cursor()).isEqualTo("a%3Db")
    }

    @Test
    fun `a private order reads bybit's none as absent, and a real value as it is`() {
        val base = firstRecord("private/order-limit-new.json")
        val none =
            BybitPrivateJson.order(
                base.with(
                    "price" to "0.00",
                    "triggerPrice" to "0",
                    "stopOrderType" to "UNKNOWN",
                    "avgPrice" to "0",
                    "rejectReason" to "EC_NoError",
                    "reduceOnly" to "false",
                    "positionIdx" to null,
                ),
            )
        val set =
            BybitPrivateJson.order(
                base.with(
                    "price" to "5.5",
                    "triggerPrice" to "6",
                    "stopOrderType" to "StopLoss",
                    "avgPrice" to "5.4",
                    "rejectReason" to "EC_PerCancelRequest",
                    "reduceOnly" to "true",
                    "positionIdx" to "2",
                ),
            )
        val gone =
            BybitPrivateJson.order(
                base
                    .with("price" to null, "triggerPrice" to null, "stopOrderType" to null, "avgPrice" to null)
                    .with("rejectReason" to null, "reduceOnly" to null),
            )

        assertThat(listOf(none.price, none.triggerPrice, none.avgPrice, none.stopOrderType, none.rejectReason))
            .containsOnlyNulls()
        assertThat(none.reduceOnly).isFalse
        assertThat(none.positionIdx).isZero
        assertThat(set.price).isEqualTo(BigDecimal("5.5"))
        assertThat(set.triggerPrice).isEqualTo(BigDecimal("6"))
        assertThat(set.avgPrice).isEqualTo(BigDecimal("5.4"))
        assertThat(set.stopOrderType).isEqualTo("StopLoss")
        assertThat(set.rejectReason).isEqualTo("EC_PerCancelRequest")
        assertThat(set.reduceOnly).isTrue
        assertThat(set.positionIdx).isEqualTo(2)
        assertThat(listOf(gone.price, gone.triggerPrice, gone.avgPrice, gone.stopOrderType, gone.rejectReason))
            .containsOnlyNulls()
        assertThat(gone.reduceOnly).isFalse
    }

    @Test
    fun `an execution with no fee has a zero one, and a position reads none and no side as absent`() {
        val execution =
            BybitPrivateJson.execution(
                firstRecord("private/executions-stop-fired.json").with(
                    "execFee" to null,
                ),
            )
        val held = firstRecord("private/position-long.json")
        val flat =
            BybitPrivateJson.position(
                held.with("side" to "None", "avgPrice" to "0", "positionIdx" to null, "createdTime" to null),
            )
        val hedged = BybitPrivateJson.position(held.with("positionIdx" to "1", "createdTime" to "1791270000000"))
        val unsided = BybitPrivateJson.position(held.with("side" to null))

        assertThat(execution.execFee).isZero
        assertThat(flat.side).isNull()
        assertThat(flat.avgPrice).isNull()
        assertThat(flat.positionIdx).isZero
        assertThat(flat.createdMs).isNull()
        assertThat(hedged.positionIdx).isEqualTo(1)
        assertThat(hedged.createdMs).isEqualTo(1_791_270_000_000L)
        assertThat(unsided.side).isNull()
    }
}
