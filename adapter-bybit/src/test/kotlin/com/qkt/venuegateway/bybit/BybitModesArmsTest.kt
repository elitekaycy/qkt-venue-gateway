package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.bybit.client.BybitPosition
import com.qkt.venuegateway.bybit.client.BybitPrivateJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitModesArmsTest {
    private val oneWay: List<BybitPosition> =
        listOf(BybitPrivateJson.position(firstRecord("private/position-slots-one-way.json")))

    @Test
    fun `an account with no reference contract, or a reference in one-way mode, nets`() {
        val none = BybitPositionModes({ oneWay }, { null }, true, { 0L })
        val reference = BybitPositionModes({ oneWay }, { "BTCUSDT" }, true, { 0L })

        assertThat(none.accounting(emptyList())).isEqualTo(Accounting.NETTING)
        assertThat(reference.accounting(emptyList())).isEqualTo(Accounting.NETTING)
    }
}
