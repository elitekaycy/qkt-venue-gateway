package com.qkt.venued.host.market

import com.qkt.venued.adapter.VenueQuote
import com.qkt.venued.host.FakeAdapter
import com.qkt.venued.host.Gateway
import com.qkt.venued.host.journal.Journal
import com.qkt.venued.host.server.Role
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class QuoteHubTest {
    @Test
    fun `a root subscriber gets its options' quotes and not other instruments'`(
        @TempDir dir: Path,
    ) {
        val venue = FakeAdapter()
        val gateway = Gateway(venue, Journal.open(dir.resolve("j.db")), mapOf(Role.TRADER to "t")) { 1_000L }
        gateway.start()
        val hub = QuoteHub(gateway).also { it.start() }
        val got = CopyOnWriteArrayList<VenueQuote>()
        hub.subscribe(emptySet(), setOf("BTC_USDC")) { got += it }

        venue.listener!!.quote(VenueQuote("BTC_USDC-9OCT26-82000-P", BigDecimal("640"), BigDecimal("655"), timeMs = 1))
        venue.listener!!.quote(VenueQuote("BTC_USDC-PERPETUAL", BigDecimal("1"), BigDecimal("2"), timeMs = 1))

        assertThat(got.map { it.symbol }).containsExactly("BTC_USDC-9OCT26-82000-P")
        assertThat(venue.subscriptions.last()).isEqualTo(emptySet<String>() to setOf("BTC_USDC"))
        hub.close()
        gateway.close()
    }
}
