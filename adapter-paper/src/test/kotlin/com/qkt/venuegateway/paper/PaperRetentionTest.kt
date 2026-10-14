package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitTickers
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The paper venue keeps its recordings of Deribit's market as long as its settings say, and no longer. */
class PaperRetentionTest {
    private val now =
        LocalDate
            .parse("2026-10-05")
            .atTime(12, 0)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()

    /** A market that fails any call: pruning reads no market. */
    private val unused =
        java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DeribitMarketData::class.java)) {
            _,
            method,
            _,
            ->
            error("market ${method.name} is not used here")
        } as DeribitMarketData

    private fun adapter(
        dir: Path,
        settings: Map<String, String>,
    ) = PaperAdapter(AdapterContext(settings + ("settlement_check_ms" to "50"), { now }, dir), unused) { _, _ -> Idle }

    private fun record(
        dir: Path,
        kind: String,
        day: String,
    ) {
        val file = dir.resolve("$kind/BTC_USDC-PERPETUAL/$day.csv")
        Files.createDirectories(file.parent)
        Files.writeString(file, "time,x\n")
    }

    private fun days(
        dir: Path,
        kind: String,
    ) = Files.list(dir.resolve("$kind/BTC_USDC-PERPETUAL")).use { f ->
        f.map { it.fileName.toString() }.sorted().toList()
    }

    @Test
    fun `day files older than the window are deleted once the adapter connects, the newer ones kept`(
        @TempDir dir: Path,
    ) {
        listOf("depth", "open-interest").forEach { kind ->
            listOf("2026-10-01", "2026-10-04").forEach { record(dir, kind, it) }
        }
        val adapter = adapter(dir, mapOf("depth_retention_days" to "1", "open_interest_retention_days" to "2"))
        adapter.connect(HeardListener(mutableListOf()))

        val deadline = System.currentTimeMillis() + 5_000
        while ((days(dir, "depth").size > 1 || days(dir, "open-interest").size > 1) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        adapter.close()

        assertThat(days(dir, "depth")).containsExactly("2026-10-04.csv")
        assertThat(days(dir, "open-interest")).containsExactly("2026-10-04.csv")
    }

    @Test
    fun `without retention settings everything is kept, and a bad value is refused naming the setting`(
        @TempDir dir: Path,
    ) {
        record(dir, "depth", "2020-01-01")
        val adapter = adapter(dir, emptyMap())
        adapter.connect(HeardListener(mutableListOf()))
        Thread.sleep(200)
        adapter.close()

        assertThat(days(dir, "depth")).containsExactly("2020-01-01.csv")
        assertThatThrownBy { adapter(dir, mapOf("depth_retention_days" to "a week")) }
            .hasMessageContaining("setting depth_retention_days")
    }

    private object Idle : DeribitTickers {
        override fun start() {}

        override fun subscribe(names: Set<String>) {}

        override fun close() {}
    }
}
