package com.qkt.venuegateway.host.market

import com.qkt.vgp.WireInstrument
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstrumentShelfTest {
    private val day = 86_400_000L
    private val expiry = 100 * day
    private val perp = WireInstrument("BTC_USDC-PERPETUAL", "perpetual", "USDC", "1", "0.1", "0.0001", "0.0001")
    private val future =
        WireInstrument("BTC_USDC-27DEC26", "future", "USDC", "1", "2.5", "0.0001", "0.0001", expiry = expiry)
    private val option =
        WireInstrument(
            "BTC_USDC-27DEC26-90000-C",
            "option",
            "USDC",
            "1",
            "5",
            "0.01",
            "0.01",
            expiry,
            "90000",
            "call",
            "BTC_USDC",
        )

    @Test
    fun `a dated contract the venue stops listing stays listed until 30 days after its expiry, across a restart`(
        @TempDir dir: Path,
    ) {
        var now = expiry - day
        val file = dir.resolve("instruments.db")
        InstrumentShelf.open(file) { now }.use { shelf ->
            assertThat(shelf.listing(listOf(perp, future, option)).map { it.code })
                .containsExactly(perp.code, future.code, option.code)
        }
        now = expiry + 29 * day
        InstrumentShelf.open(file) { now }.use { shelf ->
            assertThat(
                shelf.listing(listOf(perp)).map { it.code },
            ).containsExactlyInAnyOrder(perp.code, future.code, option.code)
            assertThat(shelf.find(future.code, live = emptyList())).isEqualTo(future)
        }
        now = expiry + 31 * day
        InstrumentShelf.open(file) { now }.use { shelf ->
            assertThat(shelf.listing(listOf(perp)).map { it.code }).containsExactly(perp.code)
            assertThat(shelf.find(future.code, live = emptyList())).isNull()
        }
    }

    @Test
    fun `the live listing wins for a contract still listed, and a perpetual is never kept once delisted`(
        @TempDir dir: Path,
    ) {
        InstrumentShelf.open(dir.resolve("instruments.db")) { expiry - day }.use { shelf ->
            shelf.listing(listOf(perp, future))
            val retick = future.copy(tickSize = "5")

            assertThat(shelf.listing(listOf(retick)).single { it.code == future.code }.tickSize).isEqualTo("5")
            assertThat(shelf.listing(emptyList()).map { it.code }).containsExactly(future.code)
            assertThat(shelf.find(perp.code, live = emptyList())).isNull()
            assertThat(shelf.find(perp.code, live = listOf(perp))).isEqualTo(perp)
        }
    }
}
