package com.qkt.venuegateway.host

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.server.Role
import com.qkt.vgp.WirePosition
import com.qkt.vgp.WireSubmit
import java.math.BigDecimal
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The stream tells clients each position change (wire spec §4 `position`), read from the venue off its thread. */
class PositionWatchTest {
    private val venue = FakeAdapter()
    private val now = 50_000_000L
    private val perp = "BTC_USDC-PERPETUAL"

    private fun gateway(dir: Path) =
        Gateway(
            venue,
            Journal.open(dir.resolve("j.db")),
            InstrumentShelf.open(dir.resolve("i.db")) { now },
            mapOf(
                Role.TRADER to "t",
            ),
        ) { now }
            .also {
                it.start()
                venue.listener!!.connection(true, "test")
            }

    private fun fill(
        fillId: String,
        quantity: String,
    ) = VenueFill("o-1.x", "v-1", fillId, perp, Side.BUY, BigDecimal(quantity), BigDecimal("84000"), now)

    private fun positions(gateway: Gateway) =
        gateway.journal
            .eventsAfter(0)
            .filter {
                it.type == "position"
            }.map { Json.decodeFromJsonElement(WirePosition.serializer(), it.data!!) }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `a new fill journals the symbol's position as the venue holds it, read off the venue's thread`(
        @TempDir dir: Path,
    ) {
        val gateway = gateway(dir)
        gateway.desk.submit(WireSubmit("o-1.x", perp, "buy", "market", "0.3", null, null, "gtc", false))
        venue.net[perp] = BigDecimal("0.3")

        venue.listener!!.fill(fill("f1", "0.3"))

        awaitTrue { positions(gateway).isNotEmpty() }
        assertThat(positions(gateway).single().quantity).isEqualTo("0.3")
        assertThat(venue.positionReads.single()).isNotEqualTo(Thread.currentThread().name)
        gateway.close()
    }

    @Test
    fun `an unchanged position is not repeated, and a position gone flat is told as zero`(
        @TempDir dir: Path,
    ) {
        val gateway = gateway(dir)
        gateway.desk.submit(WireSubmit("o-1.x", perp, "buy", "market", "0.3", null, null, "gtc", false))
        venue.net[perp] = BigDecimal("0.3")
        gateway.positions.refresh(null)
        gateway.positions.refresh(null)
        venue.net.remove(perp)

        gateway.positions.refresh(null)

        assertThat(positions(gateway).map { it.symbol to it.quantity }).containsExactly(perp to "0.3", perp to "0")
        gateway.close()
    }

    @Test
    fun `a reconciliation tells a position the venue changed without a fill`(
        @TempDir dir: Path,
    ) {
        val gateway = gateway(dir)
        venue.net[perp] = BigDecimal("-0.1")

        Reconciler(gateway).reconcile()

        assertThat(positions(gateway).map { it.symbol to it.quantity }).containsExactly(perp to "-0.1")
        gateway.close()
    }

    @Test
    fun `positions are told even when the venue's settlement history cannot be read`(
        @TempDir dir: Path,
    ) {
        val gateway = gateway(dir)
        venue.net[perp] = BigDecimal("0.2")
        venue.settlementsUnavailable = true

        runCatching { Reconciler(gateway).reconcile() }

        assertThat(positions(gateway).map { it.symbol to it.quantity }).containsExactly(perp to "0.2")
        gateway.close()
    }
}
