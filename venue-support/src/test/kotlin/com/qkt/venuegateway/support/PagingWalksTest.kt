package com.qkt.venuegateway.support

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PagingWalksTest {
    private data class Row(
        val id: String,
        val t: Long,
    )

    private val all = listOf(Row("a", 1), Row("b", 2), Row("c", 2), Row("d", 3), Row("e", 9))

    private fun from(
        start: Long,
        limit: Int,
    ): Batch<Row> {
        val rest = all.filter { it.t >= start }
        return Batch(rest.take(limit), rest.size > limit)
    }

    @Test
    fun `a walk while the venue has more restarts at the newest millisecond and drops the overlap`() {
        val rows = Paging.whileMore(0, fetch = { from(it, 3) }, id = Row::id, time = Row::t, what = "rows")
        assertThat(rows.map { it.id }).containsExactly("a", "b", "c", "d", "e")
    }

    @Test
    fun `a walk stops at its end, at enough items, and keeps only what it is told to`() {
        val starts = mutableListOf<Long>()
        Paging.whileMore(0, 2, {
            starts += it
            from(it, 2)
        }, Row::id, Row::t, "rows")
        assertThat(starts).containsExactly(0L)
        val enough =
            Paging.whileMore(
                0,
                fetch = { from(it, 2) },
                id = Row::id,
                time = Row::t,
                what = "rows",
                enough = 2,
            )
        assertThat(enough.map { it.id }).containsExactly("a", "b")
        val kept =
            Paging.whileMore(0, fetch = { from(it, 5) }, id = Row::id, time = Row::t, what = "rows", keep = {
                it.t >
                    1
            })
        assertThat(kept.map { it.id }).containsExactly("b", "c", "d", "e")
    }

    @Test
    fun `a page that cannot move forward fails naming what was walked`() {
        assertThatThrownBy {
            Paging.whileMore(0, fetch = {
                Batch(listOf(Row("x", 0), Row("y", 0)), true)
            }, id = Row::id, time = Row::t, what = "deribit trades")
        }.hasMessage("deribit trades fill a page within 0 ms; they cannot be paged")
    }

    @Test
    fun `a backwards walk ends each page before the oldest item of the last, one item per time, oldest first`() {
        val ends = mutableListOf<Long>()
        val rows =
            Paging.backwards(1, 9, { end ->
                ends += end
                all.filter { it.t <= end }.takeLast(2)
            }, Row::t)
        assertThat(ends).containsExactly(9L, 2L, 1L)
        // c shares b's millisecond: one item per time.
        assertThat(rows.map { it.id }).containsExactly("a", "b", "d", "e")
    }

    @Test
    fun `a continuation stops at the last page, an empty one, a far enough one, or the cap`() {
        val pages =
            mapOf<Int?, Pair<List<Int>, Int?>>(
                null to (listOf(1, 2) to 1),
                1 to (listOf(3) to 2),
                2 to (emptyList<Int>() to 3),
            )
        assertThat(Paging.continuation<Int, Int>({ pages.getValue(it) })).containsExactly(1, 2, 3)
        assertThat(Paging.continuation<Int, Int>({ pages.getValue(it) }, stop = { 2 in it })).containsExactly(1, 2)
        assertThat(Paging.continuation<Int, Int>({ pages.getValue(it) }, maxItems = 2)).containsExactly(1, 2)
        assertThatThrownBy {
            Paging.continuation<Int, Int>(
                { listOf(1) to 7 },
            )
        }.hasMessageContaining("does not advance past 7")
    }
}
