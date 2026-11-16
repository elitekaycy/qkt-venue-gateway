package com.qkt.venuegateway.support

import com.qkt.venuegateway.support.JsonReads.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PagingArmsTest {
    private data class Row(
        val id: String,
        val t: Long,
    )

    @Test
    fun `a span of zero or less is refused by name`() {
        assertThatThrownBy { Paging.spans(0, 10, 0, { _, _ -> emptyList<Row>() }, Row::id, Row::t) }
            .hasMessageContaining("span must be positive")
        assertThatThrownBy { Paging.spans(0, 10, -5, { _, _ -> emptyList<Row>() }, Row::id, Row::t) }
            .hasMessageContaining("-5")
    }

    @Test
    fun `an empty range asks for nothing, and full pages that do not advance are refused`() {
        var asked = 0
        val none =
            Paging.byTime(10, 10, 2, {
                asked++
                emptyList<Row>()
            }, Row::id, Row::t)
        assertThat(none).isEmpty()
        assertThat(asked).isZero

        val stuck = listOf(Row("a", 5), Row("b", 5))
        assertThatThrownBy { Paging.byTime(5, 100, 2, { stuck }, Row::id, Row::t) }
            .hasMessageContaining("does not move forward")
    }

    @Test
    fun `a walk by time drops what is past its end and stops at a short page`() {
        val rows = listOf(Row("a", 1), Row("b", 2), Row("late", 50))

        val walked = Paging.byTime(0, 10, 5, { rows }, Row::id, Row::t)

        assertThat(walked.map { it.id }).containsExactly("a", "b")
    }

    @Test
    fun `a walk while more stops on an empty page that claims more, and one whose page does not advance is refused`() {
        val empty =
            Paging.whileMore(
                0,
                fetch = { Batch(emptyList<Row>(), true) },
                id = Row::id,
                time = Row::t,
                what = "rows",
            )
        assertThat(empty).isEmpty()

        assertThatThrownBy {
            Paging.whileMore(
                5,
                fetch = { Batch(listOf(Row("a", 5)), true) },
                id = Row::id,
                time = Row::t,
                what = "the rows",
            )
        }.hasMessageContaining("the rows fill a page within 5 ms")
    }

    @Test
    fun `a walk backwards ignores what is outside its range, ends on an empty page and on the range's start`() {
        val rows = listOf(Row("before", 1), Row("a", 10), Row("b", 20), Row("after", 99))

        val walked = Paging.backwards(5, 30, { end -> rows.filter { it.t <= end } + Row("after", 99) }, Row::t)
        val none = Paging.backwards(50, 60, { emptyList<Row>() }, Row::t)

        assertThat(walked.map { it.id }).containsExactly("a", "b")
        assertThat(none).isEmpty()
    }

    @Test
    fun `a continuation that repeats itself is refused, and ends on an empty page or its stop rule`() {
        assertThatThrownBy { Paging.continuation<Row, String>({ Row("a", 1).let { r -> listOf(r) to "same" } }) }
            .hasMessageContaining("does not advance past same")
        val emptyEnds = Paging.continuation<Row, String>({ emptyList<Row>() to "more" })
        val stopped = Paging.continuation<Row, String>({ listOf(Row("a", 1)) to "next" }, stop = { true })
        val capped = Paging.continuation<Row, Int>({ c -> listOf(Row("r${c ?: 0}", 1)) to (c ?: 0) + 1 }, maxItems = 3)

        assertThat(emptyEnds).isEmpty()
        assertThat(stopped).hasSize(1)
        assertThat(capped).hasSize(3)
    }

    @Test
    fun `a field reads as text only when it is a non-empty primitive`() {
        val o =
            JsonObject(
                mapOf(
                    "nothing" to JsonNull,
                    "empty" to JsonPrimitive(""),
                    "list" to JsonArray(listOf(JsonPrimitive("x"))),
                    "word" to JsonPrimitive("x"),
                    "number" to JsonPrimitive(5),
                ),
            )

        assertThat(listOf(o.text("nothing"), o.text("empty"), o.text("list"), o.text("absent"))).containsOnlyNulls()
        assertThat(o.text("word")).isEqualTo("x")
        assertThat(o.text("number")).isEqualTo("5")
    }
}
