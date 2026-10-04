package com.qkt.venuegateway.host

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.journal.funding
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.server.Role
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReconcilerFundingTest {
    private val venue = FakeAdapter()
    private val now = 10_000_000L

    private fun gateway(dir: Path) =
        Gateway(
            venue,
            Journal.open(dir.resolve("j.db")),
            InstrumentShelf.open(dir.resolve("i.db")) { now },
            mapOf(Role.TRADER to "t"),
        ) { now }.also {
            it.start()
            venue.listener!!.connection(true, "test")
        }

    private fun funding(
        id: String,
        time: Long,
    ) = VenueFunding(id, "SOL_USDC-PERPETUAL", BigDecimal("-0.5"), "USDC", BigDecimal("-150"), time)

    @Test
    fun `funding the venue reported while no push carried it is journaled once`(
        @TempDir dir: Path,
    ) {
        val gateway = gateway(dir)
        venue.funding += funding("d-1", now - 2_000)
        venue.funding += funding("d-2", now - 1_000)

        val reconciler = Reconciler(gateway)
        reconciler.reconcile()
        reconciler.reconcile()

        assertThat(gateway.journal.eventsAfter(0).map { it.type }).containsExactly("funding", "funding")
        assertThat(gateway.journal.funding(0, now).map { it.amount }).containsExactly("-0.5", "-0.5")
        gateway.close()
    }

    @Test
    fun `an adapter is never asked for settlements or funding it does not declare`(
        @TempDir dir: Path,
    ) {
        venue.capabilities = setOf(Capability.BARS, Capability.QUOTES)
        venue.settlementsUnavailable = true
        venue.settlements += VenueSettlement("BTC_USDC-27DEC26", BigDecimal("84100"), now - 500)
        venue.funding += funding("d-1", now - 1_000)
        val gateway = gateway(dir)

        Reconciler(gateway).reconcile()

        assertThat(gateway.journal.eventsAfter(0)).isEmpty()
        gateway.close()
    }
}
