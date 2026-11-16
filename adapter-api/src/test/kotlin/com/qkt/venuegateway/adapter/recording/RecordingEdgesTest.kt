package com.qkt.venuegateway.adapter.recording

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenuePrint
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RecordingEdgesTest {
    @TempDir lateinit var dir: Path
    private val long = { 10_000_000_000L }
    private val taped = CopyOnWriteArrayList<Collection<String>>()

    private fun recorded(
        kind: String,
        text: String,
    ) {
        Files.createDirectories(dir.resolve("$kind/X"))
        Files.writeString(dir.resolve("$kind/X/1970-01-01.csv"), text)
    }

    private fun tape() = RecordedTape(dir, long, 0) { taped += it }

    @Test
    fun `a tape resumes nothing when nothing was taped, and every code taped before a restart`() {
        tape().resume()
        Files.writeString(dir.resolve("taped.txt"), "A\n\nB\n")
        tape().resume()

        assertThat(taped).containsExactly(listOf("A", "B"))
    }

    @Test
    fun `a liquidation is kept as the position it liquidated and read back as the order that closed it`() {
        val tape = tape()
        tape.onLiquidations("X", listOf(VenuePrint("l-1", 1_000L, BigDecimal.TEN, BigDecimal.ONE, Side.SELL)))

        val read = tape.liquidations("X", 0, 2_000)

        assertThat(Files.readString(dir.resolve("liquidations/X/1970-01-01.csv"))).isEqualTo("1000,l-1,10,1,Buy\n")
        assertThat(read.single().side).isEqualTo(Side.SELL)
        assertThat(tape.trades("X", 5, 5, 10) { emptyList() }).isEmpty()
    }

    @Test
    fun `a taped line with another shape or side fails naming it`() {
        recorded("trades", "1000,t-1,1,1,Hold\n")
        assertThatThrownBy { tape().trades("X", 0, 2_000, 10) { emptyList() } }
            .hasMessage("a taped side is Buy or Sell: Hold")
        recorded("trades", "1000,t-1,1\n")
        assertThatThrownBy { tape().trades("X", 0, 2_000, 10) { emptyList() } }
            .hasMessage("a taped line is time,id,price,size,side: 1000,t-1,1")
    }

    @Test
    fun `a depth line without three cells, a time or readable levels fails naming its line`() {
        val depth = RecordedDepth(dir, false, long, 0)
        val read = { depth.read("X", 0, 2_000) { error("the present book is not asked for a past window") } }

        recorded("depth", "1000,,101:2\n")
        assertThat(read().single().bids).isEmpty()
        listOf("1000,99:1", "soon,99:1,101:1").forEach { line ->
            recorded("depth", "$line\n")
            assertThatThrownBy { read() }.hasMessageEndingWith("line 1: expected time,bids,asks: $line")
        }
        recorded("depth", "1000,99,101:1\n")
        assertThatThrownBy { read() }.hasMessageEndingWith("line 1: unreadable levels: 1000,99,101:1")
        assertThat(depth.read("X", 3_000, 2_000) { error("not asked") }).isEmpty()
    }

    @Test
    fun `an open-interest line that is not a time and a figure fails naming its line`() {
        val openInterest = RecordedOpenInterest(dir, long, 0)

        listOf("1000", "1000,many", "soon,1", "1000,1,2").forEach { line ->
            recorded("open-interest", "time,open_interest\n$line\n")
            assertThatThrownBy { openInterest.read("X", 0, 2_000) { null } }
                .hasMessageEndingWith("line 2: expected time,open_interest: $line")
        }
        assertThat(openInterest.read("X", 3_000, 2_000) { null }).isEmpty()
    }
}
