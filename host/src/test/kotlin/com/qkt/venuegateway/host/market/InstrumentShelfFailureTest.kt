package com.qkt.venuegateway.host.market

import com.qkt.vgp.WireInstrument
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstrumentShelfFailureTest {
    private val future =
        WireInstrument("BTC_USDC-27DEC26", "future", "USDC", "1", "2.5", "0.0001", "0.0001", expiry = 100_000_000_000L)

    @Test
    fun `a write the shelf's file refuses is raised, and the shelf still answers the live listing afterwards`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("instruments.db")
        InstrumentShelf.open(file) { 1_000L }.use { shelf ->
            DriverManager.getConnection("jdbc:sqlite:$file").use { other ->
                other.createStatement().use { it.execute("DROP TABLE instruments") }
            }

            assertThatThrownBy { shelf.listing(listOf(future)) }.isInstanceOf(SQLException::class.java)
            assertThat(shelf.listing(emptyList())).isEmpty()
        }
    }
}
