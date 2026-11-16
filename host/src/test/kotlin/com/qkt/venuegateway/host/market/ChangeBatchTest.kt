package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.VenueQuote
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChangeBatchTest {
    private val sent = CopyOnWriteArrayList<List<VenueQuote>>()
    private val batch = ChangeBatch { sent += it }

    private fun quote(
        code: String,
        bid: String,
        time: Long = 1,
    ) = VenueQuote(code, BigDecimal(bid), BigDecimal("100"), timeMs = time)

    @Test
    fun `changes waiting for one flush go out as one batch, the latest of each code`() {
        batch.offer(quote("A", "1"))
        batch.offer(quote("A", "2"))
        batch.offer(quote("B", "1"))
        batch.flush()
        batch.flush()

        assertThat(sent).containsExactly(listOf(quote("A", "2"), quote("B", "1")))
    }

    @Test
    fun `a quote that only moved in time is not sent again, and a change undone before the flush is dropped`() {
        batch.offer(quote("A", "1"))
        batch.flush()
        batch.offer(quote("A", "1", time = 2))
        batch.offer(quote("A", "3"))
        batch.offer(quote("A", "1", time = 3))
        batch.flush()

        assertThat(sent).hasSize(1)
    }

    @Test
    fun `a heartbeat sends what is held as one batch, superseding what waits, and nothing when nothing is held`() {
        batch.offer(quote("A", "2"))
        batch.heartbeat(emptyList())
        batch.flush()
        batch.heartbeat(listOf(quote("A", "2", time = 9)))
        batch.offer(quote("A", "2", time = 10))
        batch.flush()

        assertThat(sent).containsExactly(listOf(quote("A", "2", time = 9)))
    }
}
