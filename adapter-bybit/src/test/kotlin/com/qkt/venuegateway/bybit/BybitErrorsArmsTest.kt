package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.bybit.client.BybitException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitErrorsArmsTest {
    @Test
    fun `an http status below the server errors that is not a limit is a refusal, and 599 is the last outage`() {
        assertThat(
            BybitErrors.translate(BybitException(404, "HTTP 404")),
        ).isInstanceOf(VenueRefusedException::class.java)
        assertThat(BybitErrors.translate(BybitException(499, "x"))).isInstanceOf(VenueRefusedException::class.java)
        assertThat(BybitErrors.translate(BybitException(500, "x"))).isInstanceOf(VenueUnavailableException::class.java)
        assertThat(BybitErrors.translate(BybitException(599, "x"))).isInstanceOf(VenueUnavailableException::class.java)
        assertThat(BybitErrors.translate(BybitException(600, "x"))).isInstanceOf(VenueRefusedException::class.java)
    }
}
