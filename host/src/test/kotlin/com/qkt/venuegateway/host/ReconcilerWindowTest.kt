package com.qkt.venuegateway.host

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.server.Role
import com.qkt.vgp.WireSubmit
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The venue history the reconciler reads: from its last full run, whatever arrived by push since. */
class ReconcilerWindowTest {
    private val venue = FakeAdapter()
    private var now = 100_000_000L
    private val minute = 60_000L

    private fun gateway(dir: Path) =
        Gateway(
            venue,
            Journal.open(dir.resolve("j.db")),
            InstrumentShelf.open(dir.resolve("i.db")) { now },
            mapOf(
                Role.TRADER to "t",
            ),
        ) {
            now
        }.also {
            it.start()
            venue.listener!!.connection(true, "test")
        }

    private fun fill(
        id: String,
        fillId: String,
        time: Long,
    ) = VenueFill(id, "v", fillId, "BTC_USDC-PERPETUAL", Side.BUY, BigDecimal("0.1"), BigDecimal("84000"), time)

    private fun placed(
        gateway: Gateway,
        id: String,
    ) = gateway.desk.submit(WireSubmit(id, "BTC_USDC-PERPETUAL", "buy", "limit", "0.1", "80000", null, "gtc", false))

    @Test
    fun `a fill missed in an outage is read even after a later fill arrived by push`(
        @TempDir dir: Path,
    ) {
        val gateway = gateway(dir)
        placed(gateway, "a-1.x")
        placed(gateway, "b-1.x")
        val reconciler = Reconciler(gateway)
        reconciler.reconcile()

        now += 20 * minute
        venue.fills += fill("a-1.x", "missed", now - 15 * minute)
        venue.listener!!.fill(fill("b-1.x", "pushed", now))
        reconciler.reconcile()

        assertThat(gateway.journal.fills(0, now).map { it.fillId }).containsExactlyInAnyOrder("missed", "pushed")
        gateway.close()
    }

    @Test
    fun `a venue that cannot report positions still has its fills reconciled`(
        @TempDir dir: Path,
    ) {
        val gateway = gateway(dir)
        placed(gateway, "a-1.x")
        venue.fills += fill("a-1.x", "f1", now - minute)
        venue.positionsUnavailable = true

        Reconciler(gateway).reconcile()

        assertThat(gateway.journal.fills(0, now).map { it.fillId }).containsExactly("f1")
        gateway.close()
    }
}
