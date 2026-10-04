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
                journal.updateKillSwitch {
                    it.copy(
                        symbols = (it.symbols + s).distinct(),
                    )
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        assertThat(journal.killSwitch().symbols).containsExactlyInAnyOrderElementsOf(symbols)
        assertThat(journal.updateKillSwitch { WireKillSwitch(all = true) }.all).isTrue()
        journal.close()
    }
}
