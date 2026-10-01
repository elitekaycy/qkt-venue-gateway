package com.qkt.venuegateway.host.journal

import com.qkt.vgp.WireFill
import com.qkt.vgp.WireKillSwitch
import com.qkt.vgp.WireOrder
import com.qkt.vgp.WireSettlement
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JournalTest {
    private fun order(
        id: String,
        status: String = "working",
    ) = WireOrder(
        id,
        "v-$id",
        "BTC_USDC-PERPETUAL",
        "buy",
        "market",
        "0.1",
        null,
        null,
        "gtc",
        false,
        status,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun submit(id: String) =
        com.qkt.vgp.WireSubmit(id, "BTC_USDC-PERPETUAL", "buy", "market", "0.1", null, null, "gtc", false)

    private fun fill(
        id: String,
        fillId: String,
        time: Long = 10,
    ) = WireFill(id, "v-$id", fillId, "BTC_USDC-PERPETUAL", "buy", "0.1", "84000", time)

    @Test
    fun `every event takes the next sequence number, and a fill seen twice is journaled once`(
        @TempDir dir: Path,
    ) {
        Journal.open(dir.resolve("j.db")).use { journal ->
            journal.appendOrder(order("a-1"))
            assertThat(journal.appendFill(fill("a-1", "f1"))).isTrue()
            assertThat(journal.appendFill(fill("a-1", "f1"))).isFalse()
            journal.appendSettlement(WireSettlement("BTC_USDC-27DEC26", "84100", 20))
            assertThat(journal.appendSettlement(WireSettlement("BTC_USDC-27DEC26", "84100", 20))).isFalse()

            assertThat(journal.eventsAfter(0).map { it.seq to it.type })
                .containsExactly(1L to "order", 2L to "fill", 3L to "settlement")
            assertThat(journal.eventsAfter(1).map { it.seq }).containsExactly(2L, 3L)
            assertThat(journal.latestSeq()).isEqualTo(3L)
            assertThat(journal.eventsAfter(0).all { it.stream == journal.stream }).isTrue()
        }
    }

    @Test
    fun `a reopened journal keeps its stream and state, a lost one starts a new stream`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("j.db")
        val first =
            Journal.open(file).use { journal ->
                journal.writeAhead(submit("a-1"), "hash-1")
                journal.updateOrder(order("a-1"))
                journal.markDead("a-9")
                journal.setKillSwitch(WireKillSwitch(all = false, symbols = listOf("BTC_USDC-PERPETUAL")))
                journal.appendFill(fill("a-1", "f1"))
                journal.stream
            }

        Journal.open(file).use { journal ->
            assertThat(journal.stream).isEqualTo(first)
            assertThat(journal.latestSeq()).isEqualTo(1L)
            assertThat(journal.order("a-1")?.bodyHash).isEqualTo("hash-1")
            assertThat(journal.order("a-1")?.order?.status).isEqualTo("working")
            assertThat(journal.isDead("a-9")).isTrue()
            assertThat(journal.killSwitch().symbols).containsExactly("BTC_USDC-PERPETUAL")
            assertThat(journal.workingOrders().map { it.clientOrderId }).containsExactly("a-1")
            assertThat(journal.fills(0, 100).map { it.fillId }).containsExactly("f1")
            assertThat(journal.fillsOf("a-1")).hasSize(1)
        }
        Files.delete(file)
        Journal.open(file).use { journal ->
            assertThat(journal.stream).isNotEqualTo(first)
            assertThat(journal.latestSeq()).isEqualTo(0L)
        }
    }

    @Test
    fun `an order that ended leaves the working list, and settlements read back by window and symbol`(
        @TempDir dir: Path,
    ) {
        Journal.open(dir.resolve("j.db")).use { journal ->
            journal.writeAhead(submit("a-1"), "h")
            journal.updateOrder(order("a-1"))
            journal.updateOrder(order("a-1", status = "filled"))
            journal.appendSettlement(WireSettlement("BTC_USDC-27DEC26", "84100", 20))

            assertThat(journal.workingOrders()).isEmpty()
            assertThat(journal.settlements(0, 30).map { it.symbol }).containsExactly("BTC_USDC-27DEC26")
            assertThat(journal.settlements(21, 30)).isEmpty()
            assertThat(journal.settlementsOf("BTC_USDC-27DEC26")).hasSize(1)
        }
    }
}
