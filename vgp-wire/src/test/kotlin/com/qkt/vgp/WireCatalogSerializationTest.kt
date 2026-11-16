package com.qkt.vgp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Every field of the catalog's records, written and read back by the wire's JSON with and without its defaults. */
class WireCatalogSerializationTest {
    private val plain = Json
    private val withDefaults = Json { encodeDefaults = true }
    private val writers = listOf(plain, withDefaults)

    private val bare = WireInstrument("KXINXU-26NOV06H1500-B7450", "event", "USD", "1", "0.01", "0.01", "1")
    private val full =
        bare.copy(
            expiry = 3_000,
            strike = "7450",
            right = "call",
            underlying = "INX",
            category = "Financials",
            trading = "full",
            series = "KXINXU",
            event = "KXINXU-26NOV06H1500",
            strikeType = "between",
            floor = "7425",
            cap = "7475",
            openAt = 1_000,
            closeAt = 2_000,
            occursAt = 2_500,
            occasion = "close",
            canCloseEarly = true,
            exclusive = false,
            frequency = "daily",
            title = "S&P 500 at close",
            outcome = "1",
            feeModel = "probability",
            takerFeeRate = "0.07",
            makerFeeRate = "0.0175",
            feeRounding = "0.01",
            tickRanges = listOf(WireTickRange("0", "0.1", "0.001"), WireTickRange("0.1", "0.9", "0.01")),
        )

    private fun <T> roundTrip(
        json: Json,
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
    ): T = json.decodeFromString(serializer, json.encodeToString(serializer, value))

    @Test
    fun `an instrument keeps every field, given or absent, through either writer`() {
        writers.forEach { json ->
            assertThat(roundTrip(json, WireInstrument.serializer(), full)).isEqualTo(full)
            assertThat(roundTrip(json, WireInstrument.serializer(), bare)).isEqualTo(bare)
        }
        assertThat(plain.encodeToString(WireInstrument.serializer(), bare)).doesNotContain("expiry", "tick_ranges")
        assertThat(
            withDefaults.encodeToString(WireInstrument.serializer(), bare),
        ).contains("\"expiry\":null", "\"tick_ranges\":null")
        assertThat(
            plain.encodeToString(WireInstrument.serializer(), full),
        ).contains("\"strike_type\":\"between\"", "\"can_close_early\":true")
    }

    @Test
    fun `an instrument or tick range missing a required field is refused naming it`() {
        val written = plain.encodeToJsonElement(WireInstrument.serializer(), full).jsonObject
        listOf("code", "kind", "currency", "contract_size", "tick_size", "volume_step", "volume_min").forEach { key ->
            assertThatThrownBy { plain.decodeFromJsonElement(WireInstrument.serializer(), JsonObject(written - key)) }
                .describedAs(key)
                .hasMessageContaining(key)
        }
        listOf("from", "to", "tick").forEach { key ->
            assertThatThrownBy {
                plain.decodeFromString(
                    WireTickRange.serializer(),
                    """{"from":"0","to":"1","tick":"0.1"}""".replace("\"$key\":", "\"x$key\":"),
                )
            }.describedAs(key)
                .hasMessageContaining(key)
        }
    }

    @Test
    fun `a page keeps its removals and next cursor, given or absent, through either writer`() {
        val full =
            WireInstrumentPage(listOf(bare), listOf(WireRemoved("GONE"), WireRemoved("KEPT", removed = false)), "B", 9)
        val bareListing = WireInstrumentPage(emptyList(), asOf = 9)

        writers.forEach { json ->
            assertThat(roundTrip(json, WireInstrumentPage.serializer(), full)).isEqualTo(full)
            assertThat(roundTrip(json, WireInstrumentPage.serializer(), bareListing)).isEqualTo(bareListing)
        }
        assertThat(plain.encodeToString(WireRemoved.serializer(), WireRemoved("GONE"))).isEqualTo("""{"code":"GONE"}""")
        assertThat(
            withDefaults.encodeToString(WireRemoved.serializer(), WireRemoved("GONE")),
        ).isEqualTo("""{"code":"GONE","removed":true}""")
        assertThat(
            plain.decodeFromString(WireRemoved.serializer(), """{"code":"GONE","removed":false}""").removed,
        ).isFalse
        assertThatThrownBy {
            plain.decodeFromString(WireRemoved.serializer(), """{"removed":true}""")
        }.hasMessageContaining("code")
        assertThatThrownBy { plain.decodeFromString(WireInstrumentPage.serializer(), """{"instruments":[]}""") }
            .hasMessageContaining("as_of")
        assertThatThrownBy { plain.decodeFromString(WireInstrumentPage.serializer(), """{"as_of":1}""") }
            .hasMessageContaining("instruments")
    }
}
