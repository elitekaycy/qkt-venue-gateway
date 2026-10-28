package com.qkt.venuegateway.alpaca

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AlpacaRecentFillsTest {
    private val streamed = Fixtures.updates("ws-trade-updates-fill.json").mapNotNull(AlpacaMapping::fill)
    private val listed = Fixtures.array("activities-fill.json").map { AlpacaMapping.fill(it, "label") }

    @Test
    fun `a fill the stream pushed but the activities do not list yet is read back by time, once`() {
        var now = 0L
        val recent = AlpacaRecentFills({ now })
        streamed.forEach(recent::add)
        val all = streamed.minOf { it.timeMs } to streamed.maxOf { it.timeMs }
        assertThat(
            recent.merged(emptyList(), all.first, all.second).map {
                it.fillId
            },
        ).containsExactlyElementsOf(streamed.map { it.fillId })
        // Listed by the activities too: one per execution id, the stream's copy kept (it is net of a coin fee).
        val merged = recent.merged(listed, 0L, Long.MAX_VALUE)
        assertThat(merged.map { it.fillId }).doesNotHaveDuplicates().containsAll(streamed.map { it.fillId })
        val ids = streamed.map { it.fillId }.toSet()
        assertThat(merged.filter { it.fillId in ids }).containsExactlyInAnyOrderElementsOf(streamed)
        // Outside the range asked, or older than it keeps them, none is added.
        assertThat(recent.merged(emptyList(), 0L, 1L)).isEmpty()
        now = 700_000L
        recent.add(streamed.first())
        assertThat(recent.merged(emptyList(), 0L, Long.MAX_VALUE)).hasSize(1)
    }
}
