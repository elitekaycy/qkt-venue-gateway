package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.kalshi.client.KalshiException
import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KalshiErrorsTest {
    @Test
    fun `a rate limit and a server error are unavailable, any other answer a refusal by Kalshi's code`() {
        assertThat(KalshiErrors.translate(KalshiException(429, "too_many_requests", "slow down")))
            .isInstanceOf(VenueUnavailableException::class.java)
        assertThat(KalshiErrors.translate(KalshiException(503, "http_503", "GET /markets")))
            .isInstanceOf(VenueUnavailableException::class.java)
        assertThat(KalshiErrors.translate(KalshiException(400, "invalid_price", "invalid price")))
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("400 invalid_price")
        assertThat(KalshiErrors.translate(KalshiException(409, "order_already_exists", "order already exists")))
            .isInstanceOf(VenueRefusedException::class.java)
    }

    @Test
    fun `kalshi unreachable is unavailable, and a failure of the gateway's own passes through`() {
        assertThat(KalshiErrors.translate(IOException("reset"))).isInstanceOf(VenueUnavailableException::class.java)
        val own = IllegalStateException("bug")
        assertThat(KalshiErrors.translate(own)).isSameAs(own)
        assertThat(KalshiErrors.translate(Exception("checked"))).isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { KalshiErrors.venue { throw own } }.isSameAs(own)
        assertThatThrownBy { KalshiErrors.venue { throw KalshiException(500, "http_500", "x") } }
            .isInstanceOf(VenueUnavailableException::class.java)
    }
}
