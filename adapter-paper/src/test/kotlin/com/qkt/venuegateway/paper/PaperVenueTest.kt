package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.deribit.client.DeribitException
import java.io.IOException
import java.lang.reflect.Modifier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PaperVenueTest {
    @Test
    fun `a Deribit JSON-RPC error is a refusal, not an escaped runtime error`() {
        assertThatThrownBy { venue { throw DeribitException(10009, "not_enough_funds") } }
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("10009")
    }

    @Test
    fun `an error Deribit documents as not serving, or Deribit unreachable, is the venue unavailable`() {
        assertThatThrownBy { venue { throw DeribitException(10028, "too_many_requests") } }
            .isInstanceOf(VenueUnavailableException::class.java)
        assertThatThrownBy { venue { throw IOException("connection reset") } }
            .isInstanceOf(VenueUnavailableException::class.java)
            .hasMessageContaining("connection reset")
        assertThat(venue { "answer" }).isEqualTo("answer")
    }

    @Test
    fun `the listener connect sets is visible to the event and feed threads`() {
        val field = PaperAdapter::class.java.getDeclaredField("listener")
        assertThat(Modifier.isVolatile(field.modifiers)).isTrue
    }
}
