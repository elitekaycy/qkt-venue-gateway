package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.bybit.client.BybitException
import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitErrorsTest {
    @Test
    fun `an answer bybit gave is a refusal with its own words`() {
        listOf(
            10001 to "Qty invalid",
            110007 to "CheckMarginRatio fail! InsufficientAB",
            110001 to "order not exists",
        ).forEach { (code, words) ->
            val failure = BybitErrors.translate(BybitException(code, words))
            assertThat(failure).isInstanceOf(VenueRefusedException::class.java).hasMessage("bybit $code: $words")
        }
    }

    @Test
    fun `a timeout, a rate limit, a restart or an order still processing leaves the outcome unknown`() {
        listOf(403, 429, 502, 10000, 10002, 10006, 10016, 10018, 10019, 10429, 110079, 170007, 170032, 170234).forEach {
            assertThat(
                BybitErrors.translate(BybitException(it, "busy")),
            ).describedAs("code $it").isInstanceOf(VenueUnavailableException::class.java)
        }
    }

    @Test
    fun `bybit being unreachable is unavailable`() {
        assertThat(
            BybitErrors.translate(IOException("connection reset")),
        ).isInstanceOf(VenueUnavailableException::class.java)
    }
}
