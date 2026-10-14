package com.qkt.venuegateway.host.journal

import com.qkt.vgp.WireKillSwitch
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KillSwitchUpdateTest {
    @Test
    fun `guardians adding different symbols at once all take effect`(
        @TempDir dir: Path,
    ) {
        val journal = Journal.open(dir.resolve("j.db"))
        val pool = Executors.newFixedThreadPool(8)
        val symbols = (1..64).map { "SYM-$it" }

        symbols.forEach { s ->
            pool.execute {
                journal.updateKillSwitch(1L) {
                    it.copy(
                        symbols = (it.symbols + s).distinct(),
                    )
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        assertThat(journal.killSwitch().symbols).containsExactlyInAnyOrderElementsOf(symbols)
        assertThat(journal.updateKillSwitch(1L) { WireKillSwitch(all = true) }.all).isTrue()
        journal.close()
    }

    @Test
    fun `a change that moves the switch appends one kill event carrying it, and a no-op change none`(
        @TempDir dir: Path,
    ) {
        Journal.open(dir.resolve("j.db")).use { journal ->
            journal.updateKillSwitch(5L) { it.copy(symbols = listOf("BTC_USDC-PERPETUAL")) }
            journal.updateKillSwitch(6L) { it.copy(symbols = listOf("BTC_USDC-PERPETUAL")) }
            journal.updateKillSwitch(7L) { it.copy(symbols = emptyList()) }

            val events = journal.eventsAfter(0)
            assertThat(events.map { it.seq to it.type }).containsExactly(1L to "kill", 2L to "kill")
            assertThat(events.map { it.time }).containsExactly(5L, 7L)
            assertThat(events.first().data.toString()).contains("\"all\":false", "BTC_USDC-PERPETUAL")
            assertThat(events.last().data.toString()).contains("\"symbols\":[]")
        }
    }
}
