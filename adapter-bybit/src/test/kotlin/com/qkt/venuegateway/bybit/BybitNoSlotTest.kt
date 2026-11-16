package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitNoSlotTest {
    @Test
    fun `a contract bybit answers no position slot for is refused by name rather than guessed one-way`() {
        val modes = BybitPositionModes({ emptyList() }, { null }, true, { 0L })
        val order = NewOrder("x", "ETHUSDT", Side.BUY, OrderType.MARKET, BigDecimal.ONE, timeInForce = TimeInForce.GTC)

        assertThatThrownBy { modes.index(order) }.hasMessageContaining("no position slot of ETHUSDT")
    }
}
