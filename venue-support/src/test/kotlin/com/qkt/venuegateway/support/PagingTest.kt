package com.qkt.venuegateway.support

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PagingTest {
    private data class Row(
        val id: String,
        val t: Long,
    )

    @Test
    fun `spans cover the range in capped pieces and keep one copy of an item two spans return`() {
        val asked = mutableListOf<Pair<Long, Long>>()
        val rows =
            Paging.spans(0, 250, 100, { from, to ->
                asked += from to to
                listOf(Row("r$from", from + 1), Row("edge", 99))
            }, Row::id, Row::t)
        assertThat(asked).containsExactly(0L to 100L, 100L to 200L, 200L to 250L)
        assertThat(rows.map { it.id }).containsExactly("r0", "edge", "r100", "r200")
    }

    @Test
    fun `a cursor is followed to the last page, and one answered twice fails`() {
        val pages = mapOf(null to Page(listOf(Row("a", 2)), "c1"), "c1" to Page(listOf(Row("b", 1)), null))
        assertThat(Paging.cursor(null, { pages.getValue(it) }, Row::id, Row::t).map { it.id }).containsExactly("b", "a")
        assertThatThrownBy { Paging.cursor(null, { Page(listOf(Row("x", 1)), "same") }, Row::id, Row::t) }
            .hasMessageContaining("cursor same twice")
    }

    @Test
    fun `by time restarts at the newest millisecond seen and drops the overlap`() {
        val all = listOf(Row("a", 1), Row("b", 2), Row("c", 2), Row("d", 3), Row("e", 9))
        val rows = Paging.byTime(0, 5, limit = 3, { from -> all.filter { it.t >= from }.take(3) }, Row::id, Row::t)
        assertThat(rows.map { it.id }).containsExactly("a", "b", "c", "d")
    }

    @Test
    fun `a full page that cannot move forward fails instead of looping`() {
        assertThatThrownBy {
            Paging.byTime(0, 10, limit = 2, { listOf(Row("x", 5), Row("y", 5)) }, Row::id, Row::t)
        }.hasMessageContaining("does not move forward")
    }
}
