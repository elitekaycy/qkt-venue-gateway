package com.qkt.venuegateway.adapter

import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class VenueErrorsTest {
    private class AcmeException(
        val code: Int,
    ) : RuntimeException("acme $code")

    private val errors = VenueErrors("acme", { (it as? AcmeException)?.code }) { it == 429 }

    @Test
    fun `a venue code it documents as not serving is unavailable, any other is a refusal`() {
        assertThat(
            errors.translate(AcmeException(429)),
        ).isInstanceOf(VenueUnavailableException::class.java).hasMessage("acme 429")
        assertThat(
            errors.translate(AcmeException(400)),
        ).isInstanceOf(VenueRefusedException::class.java).hasMessage("acme 400")
    }

    @Test
    fun `unreachable is unavailable and anything else passes through untouched`() {
        assertThat(
            errors.translate(IOException("reset")),
        ).isInstanceOf(VenueUnavailableException::class.java).hasMessage("acme: reset")
        val own = IllegalArgumentException("a bug")
        assertThatThrownBy { errors.venue { throw own } }.isSameAs(own)
        assertThatThrownBy { errors.venue { throw AcmeException(400) } }.isInstanceOf(VenueRefusedException::class.java)
        assertThat(errors.venue { "answer" }).isEqualTo("answer")
    }
}
