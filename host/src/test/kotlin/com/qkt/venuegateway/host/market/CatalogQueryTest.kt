package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.EventTerms
import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.StrikeType
import com.qkt.venuegateway.adapter.Tradability
import com.qkt.venuegateway.host.wire.InvalidRequestException
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CatalogQueryTest {
    @TempDir lateinit var dir: Path
    private var now = 1_000L
    private val venue = ListedVenue()

    private val catalog by lazy { Catalog(venue, InstrumentShelf.open(dir.resolve("i.db")) { now }, clock = { now }) }

    private fun event(
        code: String,
        series: String,
        closeAt: Long = 9_000,
    ) = Instrument(
        code,
        InstrumentKind.EVENT,
        "USD",
        BigDecimal.ONE,
        BigDecimal("0.01"),
        BigDecimal.ONE,
        BigDecimal.ONE,
        expiryMs = closeAt,
        category = "Financials",
        event = EventTerms(series, "$series-E1", StrikeType.NONE, null, null, 0, closeAt),
    )

    private fun query(vararg params: Pair<String, String>) = CatalogQuery.of(params.toMap()::get)

    private fun codes(vararg params: Pair<String, String>) =
        query(*params)!!.page(catalog.current(), catalog.changes, now).instruments.map { it.code }

    @Test
    fun `no catalog parameter is no query, so the listing is served as it always was`() {
        assertThat(CatalogQuery.of { null }).isNull()
        assertThat(query("symbols" to "x")).isNull()
    }

    @Test
    fun `filters keep only the instruments matching every one given, by code`() {
        venue.listed += listOf(event("KXB-1", "KXB"), event("KXA-1", "KXA"), event("KXA-2", "KXA"))

        assertThat(codes("kind" to "event")).containsExactly("KXA-1", "KXA-2", "KXB-1")
        assertThat(codes("series" to "KXA")).containsExactly("KXA-1", "KXA-2")
        assertThat(codes("event" to "KXB-E1", "category" to "Financials")).containsExactly("KXB-1")
        assertThat(codes("category" to "Climate")).isEmpty()
    }

    @Test
    fun `open is what the venue lists and can be opened now, and closed is everything else`() {
        venue.listed += listOf(event("KXA-1", "KXA"), event("KXA-2", "KXA", closeAt = 500))
        venue.listed += event("KXA-3", "KXA").copy(trading = Tradability.CLOSE_ONLY)
        venue.listed += perpetual("OLD-FUTURE").copy(kind = InstrumentKind.FUTURE, expiryMs = 900)
        venue.listed += event("KXA-4", "KXA")
        catalog.current()
        venue.listed.removeIf { it.code == "KXA-4" }
        catalog.refresh()

        assertThat(codes("status" to "open", "kind" to "event")).containsExactly("KXA-1")
        assertThat(codes("status" to "closed")).containsExactly("KXA-2", "KXA-3", "KXA-4", "OLD-FUTURE")
        assertThat(codes("status" to "all")).hasSize(7)
    }

    @Test
    fun `a page holds at most the limit, after the cursor, and names the next one until the last`() {
        venue.listed += perpetual("ZZ-PERPETUAL")
        val first = query("limit" to "2")!!.page(catalog.current(), catalog.changes, now)
        val second = query("limit" to "2", "cursor" to first.next!!)!!.page(catalog.current(), catalog.changes, now)

        assertThat(first.instruments.map { it.code }).containsExactly("BTC_USDC-9OCT26-82000-P", "BTC_USDC-PERPETUAL")
        assertThat(first.removed).isNull()
        assertThat(second.instruments.map { it.code }).containsExactly("ZZ-PERPETUAL")
        assertThat(second.next).isNull()
        assertThat(second.asOf).isEqualTo(now)
    }

    @Test
    fun `updated_since serves what was added or changed since, and the codes removed since as last listed`() {
        venue.listed += listOf(event("KXA-1", "KXA", closeAt = 500), event("KXB-1", "KXB"))
        val before = catalog.current().readAt
        now = 500 + 7 * 86_400_000L + 1
        venue.listed.removeIf { it.code == "KXA-1" || it.code == "BTC_USDC-PERPETUAL" }
        venue.listed += event("KXA-2", "KXA", closeAt = now + 1)
        catalog.refresh()

        val all = query("updated_since" to "$now")!!.page(catalog.current(), catalog.changes, now)
        val kxa = query("updated_since" to "$before", "series" to "KXA")!!.page(catalog.current(), catalog.changes, now)

        assertThat(all.instruments.map { it.code }).containsExactly("KXA-2")
        assertThat(all.removed!!.map { it.code to it.removed })
            .containsExactly("BTC_USDC-PERPETUAL" to true, "KXA-1" to true)
        assertThat(kxa.instruments.map { it.code }).containsExactly("KXA-2")
        assertThat(kxa.removed!!.map { it.code }).containsExactly("KXA-1")
        assertThat(codes("updated_since" to "${now + 1}")).isEmpty()
    }

    @Test
    fun `updated_since from before the gateway's first read or its removal memory is refused, to read in full`() {
        catalog.current()
        assertThatThrownBy { codes("updated_since" to "999") }.hasMessageContaining("read in full")
        venue.listed.removeIf { it.code == "BTC_USDC-PERPETUAL" }
        now = 2_000
        catalog.refresh()
        now = 8 * 86_400_000L
        catalog.refresh()
        assertThat(catalog.changes.removedSince(0)).isEmpty()
        assertThatThrownBy { codes("updated_since" to "2000") }.isInstanceOf(InvalidRequestException::class.java)
    }

    @Test
    fun `a bad status, time or limit is refused by name`() {
        assertThatThrownBy { query("status" to "live") }.hasMessageContaining("status")
        assertThatThrownBy { query("updated_since" to "yesterday") }.hasMessageContaining("updated_since")
        listOf("0", "5001", "many").forEach { bad ->
            assertThatThrownBy { query("limit" to bad) }.hasMessageContaining("limit").hasMessageContaining(bad)
        }
        assertThat(query("limit" to "5000")!!.limit).isEqualTo(CatalogQuery.MAX_LIMIT)
    }
}
