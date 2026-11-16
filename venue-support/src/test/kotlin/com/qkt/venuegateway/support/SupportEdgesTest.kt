package com.qkt.venuegateway.support

import com.qkt.venuegateway.support.JsonReads.bool
import com.qkt.venuegateway.support.JsonReads.instantMs
import com.qkt.venuegateway.support.JsonReads.longOrNull
import com.qkt.venuegateway.support.JsonReads.obj
import com.qkt.venuegateway.support.JsonReads.objOrNull
import com.qkt.venuegateway.support.JsonReads.objects
import com.qkt.venuegateway.support.JsonReads.text
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SupportEdgesTest {
    private val venue =
        Json
            .parseToJsonElement(
                """{"null":null,"empty":"","nested":{"a":1},"ms":"1700000000000.0","flag":"true",""" +
                    """"at":"2026-10-28T02:44:03.5+02:00","mixed":[{"a":1},2],"word":"x","list":[{"a":1}]}""",
            ).jsonObject

    @Test
    fun `null, empty and non-scalar fields read as absent text`() {
        assertThat(listOf("null", "empty", "nested", "gone").map { venue.text(it) }).containsOnlyNulls()
        assertThat(venue.text("word")).isEqualTo("x")
        assertThat(venue.bool("flag")).isTrue
        assertThat(venue.bool("gone")).isNull()
    }

    @Test
    fun `optional whole numbers, objects and times read when present and are null when not`() {
        assertThat(venue.longOrNull("ms")).isEqualTo(1_700_000_000_000L)
        assertThat(venue.longOrNull("gone")).isNull()
        assertThat(venue.objOrNull("nested")).isEqualTo(buildJsonObject { put("a", JsonPrimitive(1)) })
        assertThat(venue.objOrNull("word")).isNull()
        assertThat(venue.obj("nested").keys).containsExactly("a")
        assertThatThrownBy { venue.obj("word") }.hasMessageStartingWith("field word missing (has [at, empty,")
        assertThat(venue.instantMs("at")).isEqualTo(1_793_148_243_500L)
        assertThat(venue.instantMs("gone")).isNull()
    }

    @Test
    fun `an array of objects reads, an absent one is empty, and anything else fails naming the field`() {
        assertThat(venue.objects("list")).hasSize(1)
        assertThat(venue.objects("null")).isEmpty()
        assertThat(venue.objects("gone")).isEmpty()
        assertThatThrownBy { venue.objects("mixed") }.hasMessage("field mixed holds a non-object")
        assertThatThrownBy { venue.objects("word") }.hasMessage("field word is not an array")
        assertThat(JsonReads.objects(venue.getValue("list"), "orders")).hasSize(1)
        assertThatThrownBy { JsonReads.objects(venue.getValue("word"), "orders") }.hasMessage("orders is not an array")
        assertThatThrownBy { JsonReads.objects(venue.getValue("mixed"), "orders") }
            .hasMessage("orders holds a non-object")
    }

    @Test
    fun `a read retried by the clock answers at once, or gives up after its attempts, and needs one attempt`() {
        var calls = 0

        assertThat(Retry.until(3, 1) { "found" }).isEqualTo("found")
        assertThat(Retry.until<String>(2, 1) { null.also { calls++ } }).isNull()
        assertThat(calls).isEqualTo(2)
        assertThatThrownBy { Retry.until(0, 1) { "x" } }.hasMessage("attempts must be positive: 0")
    }

    @Test
    fun `cursor paging starts with no cursor unless given one`() {
        val asked = CopyOnWriteArrayList<String?>()

        val items =
            Paging.cursor(
                fetch = { cursor: String? ->
                    asked += cursor
                    Page(listOf(cursor ?: "start"), if (cursor == null) "c-1" else null)
                },
                id = { it },
                time = { 0L },
            )

        assertThat(asked).containsExactly(null, "c-1")
        assertThat(items).containsExactly("start", "c-1")
    }

    @Test
    fun `a patch is sent with its body under the default timeout`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = """{"ok":true}"""))

            val http = JsonHttp(server.url("").toString().trimEnd('/'))
            val answer = http.patch("/v1/o/1", buildJsonObject { put("q", JsonPrimitive("2")) })

            val sent = server.takeRequest()
            assertThat(answer.status).isEqualTo(200)
            assertThat(sent.method).isEqualTo("PATCH")
            assertThat(sent.body?.utf8()).isEqualTo("""{"q":"2"}""")
        }
    }

    @Test
    fun `a socket protocol that only reads frames takes no action when opening, up or dropped`() {
        val sent = CopyOnWriteArrayList<String>()
        val frames = CopyOnWriteArrayList<String>()
        val protocol =
            object : SocketProtocol {
                override fun text(frame: String) {
                    frames += frame
                }
            }

        protocol.opening { sent.add(it) }
        protocol.up()
        protocol.dropped("gone", wasUp = true)
        protocol.text("frame")

        assertThat(sent).isEmpty()
        assertThat(frames).containsExactly("frame")
    }
}
