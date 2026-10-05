package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** An older gateway's single open-interest file, split into day files on upgrade without losing a figure. */
class DeribitOpenInterestSplitTest {
    private val d1 =
        LocalDate
            .parse("2026-10-03")
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli() + 60_000
    private val d2 = d1 + 86_400_000
    private val now = d2 + 86_400_000

    private fun openInterest(
        dir: Path,
        days: Int = 0,
    ) = DeribitOpenInterest({ code -> error("$code: the ticker is not read in the past") as DeribitTicker }, dir, {
        now
    }, days)

    private fun single(dir: Path): Path =
        dir.resolve("open-interest/BTC_USDC-PERPETUAL.csv").also { Files.createDirectories(it.parent) }

    private fun day(
        dir: Path,
        day: String,
    ) = Files.readAllLines(dir.resolve("open-interest/BTC_USDC-PERPETUAL/$day.csv"))

    private fun served(dir: Path) =
        openInterest(dir).read("BTC_USDC-PERPETUAL", 0, d2 + 1_000).map { it.timeMs to it.openInterest.toPlainString() }

    @Test
    fun `the single file is split into one file a day on its first read, every figure kept, and deleted`(
        @TempDir dir: Path,
    ) {
        Files.writeString(single(dir), "time,open_interest\n$d1,1470\n${d1 + 60_000},1471.5\n$d2,1480\n")

        val first = openInterest(dir).read("BTC_USDC-PERPETUAL", 0, d2)

        assertThat(first.map { it.timeMs to it.openInterest.toPlainString() })
            .containsExactly(d1 to "1470", d1 + 60_000 to "1471.5", d2 to "1480")
        assertThat(Files.exists(single(dir))).isFalse
        assertThat(day(dir, "2026-10-03")).containsExactly("time,open_interest", "$d1,1470", "${d1 + 60_000},1471.5")
        assertThat(day(dir, "2026-10-04")).containsExactly("time,open_interest", "$d2,1480")
        assertThat(served(dir)).isEqualTo(first.map { it.timeMs to it.openInterest.toPlainString() })
    }

    @Test
    fun `a split a crash cut short is done again on the next read, each figure kept once`(
        @TempDir dir: Path,
    ) {
        Files.writeString(single(dir), "time,open_interest\n$d1,1470\n$d2,1480\n")
        Files.createDirectories(dir.resolve("open-interest/BTC_USDC-PERPETUAL"))
        Files.writeString(
            dir.resolve("open-interest/BTC_USDC-PERPETUAL/2026-10-03.csv"),
            "time,open_interest\n$d1,1470\n",
        )

        assertThat(served(dir)).containsExactly(d1 to "1470", d2 to "1480")
        assertThat(day(dir, "2026-10-03")).containsExactly("time,open_interest", "$d1,1470")
    }

    @Test
    fun `a torn last line of the single file is dropped and the complete figures are kept`(
        @TempDir dir: Path,
    ) {
        Files.writeString(single(dir), "time,open_interest\n$d1,1470\n$d2,14")

        assertThat(served(dir)).containsExactly(d1 to "1470")
        assertThat(Files.exists(single(dir))).isFalse
    }

    @Test
    fun `a malformed line of the single file fails naming it, and the file is left as it was`(
        @TempDir dir: Path,
    ) {
        Files.writeString(single(dir), "time,open_interest\n$d1,1470\n$d2,14x0\n")

        assertThatThrownBy { served(dir) }.hasMessageContaining("BTC_USDC-PERPETUAL.csv line 3")
        assertThat(Files.readString(single(dir))).endsWith("14x0\n")
        assertThat(Files.exists(dir.resolve("open-interest/BTC_USDC-PERPETUAL"))).isFalse
    }

    @Test
    fun `a split record is pruned like any other`(
        @TempDir dir: Path,
    ) {
        Files.writeString(single(dir), "time,open_interest\n$d1,1470\n$d2,1480\n")
        val series = openInterest(dir, days = 1)

        assertThat(series.read("BTC_USDC-PERPETUAL", 0, d2).map { it.timeMs }).containsExactly(d1, d2)
        assertThat(series.prune()).isEqualTo(1)
        assertThat(series.read("BTC_USDC-PERPETUAL", 0, d2).map { it.timeMs }).containsExactly(d2)
    }
}
