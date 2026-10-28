package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.alpaca.client.AlpacaHttpException
import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AlpacaErrorsTest {
    private fun refusal(
        status: Int,
        fixture: String,
    ) = AlpacaErrors.translate(AlpacaHttpException(status, Fixtures.text(fixture)))

    @Test
    fun `an order Alpaca will not do is a refusal carrying Alpaca's own reason`() {
        val cases =
            mapOf(
                (403 to "error-buying-power.json") to "insufficient buying power",
                (422 to "error-unknown-symbol.json") to "not found",
                (422 to "error-sub-penny.json") to "sub-penny",
                (422 to "error-crypto-day-tif.json") to "invalid crypto time_in_force",
                (403 to "error-crypto-below-min.json") to "minimal amount of order",
                (403 to "error-crypto-short.json") to "insufficient balance",
                (422 to "error-extended-hours-market.json") to "extended hours",
                (422 to "order-replace.json") to "cannot replace order",
            )
        for ((call, reason) in cases) {
            val failure = refusal(call.first, call.second)
            assertThat(failure).isInstanceOf(VenueRefusedException::class.java)
            assertThat((failure as VenueRefusedException).reason).containsIgnoringCase(reason)
        }
    }

    @Test
    fun `a refused key, a rate limit, a server error or no answer at all is unavailable, never a refusal`() {
        for (status in listOf(401, 429, 500, 503)) {
            assertThat(
                AlpacaErrors.translate(AlpacaHttpException(status, "")),
            ).isInstanceOf(VenueUnavailableException::class.java)
        }
        assertThat(AlpacaErrors.translate(IOException("reset"))).isInstanceOf(VenueUnavailableException::class.java)
    }

    @Test
    fun `Alpaca's code is read from its answer, so a duplicate label can be told from another malformed order`() {
        assertThat(
            AlpacaErrors.code(AlpacaHttpException(422, Fixtures.text("order-duplicate-label.json"))),
        ).isEqualTo("40010001")
        assertThat(AlpacaErrors.code(AlpacaHttpException(500, "<html>"))).isNull()
    }
}
