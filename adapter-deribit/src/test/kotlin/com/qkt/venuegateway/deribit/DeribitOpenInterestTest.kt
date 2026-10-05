package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** What the gateway records and serves of Deribit's open interest, which Deribit publishes only as a present figure. */
class DeribitOpenInterestTest {
    private val recorded: DeribitTicker =
        DeribitJson.ticker(
            Json
                .parseToJsonElement(javaClass.getResource("/fixtures/ticker-perp.json")!!.readText())
                .jsonObject
                .getValue("result")
                .jsonObject,
        )
    private val at = recorded.timestampMs
    private var now = at
    private val asked = mutableListOf<String>()
    private var answer = recorded

    private fun openInterest(dir: Path) = DeribitOpenInterest({ code -> answer.also { asked += code } }, dir, { now })

    @Test
    fun `a recorded ticker's open interest is its contracts outstanding in coins, known at the ticker's time`() {
        val figure = DeribitMarketMapping.openInterest(recorded)!!

        assertThat(figure.timeMs).isEqualTo(1_790_864_291_655L)
        assertThat(figure.openInterest.toPlainString()).isEqualTo("1477.6341")
    }

    @Test
    fun `a read reaching the present records the ticker's figure and serves it again after a restart`(
        @TempDir dir: Path,
    ) {
        val first = openInterest(dir).read("BTC_USDC-PERPETUAL", at - 60_000, at)
        now = at + 3_600_000
        val restarted = openInterest(dir).read("BTC_USDC-PERPETUAL", at - 60_000, at)

        assertThat(first.map { it.timeMs to it.openInterest.toPlainString() }).containsExactly(at to "1477.6341")
        assertThat(restarted).isEqualTo(first)
        assertThat(asked).containsExactly("BTC_USDC-PERPETUAL")
        assertThat(Files.readAllLines(dir.resolve("open-interest/BTC_USDC-PERPETUAL.csv")))
            .containsExactly("time,open_interest", "$at,1477.6341")
    }

    @Test
    fun `a figure no newer than the last one recorded is not recorded twice, a newer one is`(
        @TempDir dir: Path,
    ) {
        val series = openInterest(dir)
        series.read("BTC_USDC-PERPETUAL", 0, at)
        series.read("BTC_USDC-PERPETUAL", 0, at)
        answer = recorded.copy(timestampMs = at + 60_000, openInterest = BigDecimal("1480"))
        now = at + 60_000

        val served = series.read("BTC_USDC-PERPETUAL", 0, now)

        assertThat(served.map { it.openInterest.toPlainString() }).containsExactly("1477.6341", "1480")
    }

    @Test
    fun `a read of the past records nothing and a ticker without open interest records nothing`(
        @TempDir dir: Path,
    ) {
        now = at + 86_400_000
        assertThat(openInterest(dir).read("BTC_USDC-PERPETUAL", 0, at)).isEmpty()
        assertThat(asked).isEmpty()

        answer = recorded.copy(openInterest = null)
        assertThat(openInterest(dir).read("BTC_USDC-PERPETUAL", 0, now)).isEmpty()
        assertThat(asked).containsExactly("BTC_USDC-PERPETUAL")
    }

    @Test
    fun `a code that is not an instrument name is refused before anything is read or written`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { openInterest(dir).read("../BTC", 0, now) }.isInstanceOf(VenueRefusedException::class.java)
        assertThat(asked).isEmpty()
        assertThat(Files.exists(dir.resolve("open-interest"))).isFalse
    }

    @Test
    fun `a last line an append left without its newline is dropped and cut off, and recording carries on`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("open-interest/BTC_USDC-PERPETUAL.csv")
        Files.createDirectories(file.parent)
        Files.writeString(file, "time,open_interest\n${at - 60_000},1470\n${at - 1_000},147")

        val served = openInterest(dir).read("BTC_USDC-PERPETUAL", 0, at)

        assertThat(served.map { it.timeMs to it.openInterest.toPlainString() })
            .containsExactly(at - 60_000 to "1470", at to "1477.6341")
        assertThat(
            Files.readAllLines(file),
        ).containsExactly("time,open_interest", "${at - 60_000},1470", "$at,1477.6341")
    }

    @Test
    fun `a malformed complete line still fails naming the file and line`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("open-interest/BTC_USDC-PERPETUAL.csv")
        Files.createDirectories(file.parent)
        Files.writeString(file, "time,open_interest\n${at - 60_000},14x0\n")

        assertThatThrownBy { openInterest(dir).read("BTC_USDC-PERPETUAL", 0, at) }
            .hasMessageContaining("BTC_USDC-PERPETUAL.csv line 2")
        assertThat(Files.readString(file)).endsWith("14x0\n")
    }
}
