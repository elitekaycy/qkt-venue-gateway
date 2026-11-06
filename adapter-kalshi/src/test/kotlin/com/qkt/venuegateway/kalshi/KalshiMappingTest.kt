package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.FeeModel
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.StrikeType
import com.qkt.venuegateway.adapter.Tradability
import com.qkt.venuegateway.kalshi.client.KalshiEvent
import com.qkt.venuegateway.kalshi.client.KalshiMarketJson
import com.qkt.venuegateway.kalshi.client.KalshiSeries
import java.time.Instant
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KalshiMappingTest {
    private fun event(name: String): KalshiEvent =
        KalshiMarketJson.event(Fixtures.json(name)["events"]!!.jsonArray[0].jsonObject)

    private fun series(name: String): KalshiSeries =
        KalshiMarketJson.series(Fixtures.json(name)["series"]!!.jsonObject)

    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun `a weather bucket is an event contract between its bounds, its series' cadence and fee attached`() {
        val e = event("public/events-page-2.json")
        val i = KalshiMapping.instrument(e.markets[1], e, series("public/series-KXHIGHNY.json"), 0)

        assertThat(i.code).isEqualTo("KXHIGHNY-26NOV06-B67.5")
        assertThat(i.kind).isEqualTo(InstrumentKind.EVENT)
        assertThat(i.currency).isEqualTo("USD")
        assertThat(i.contractSize).isEqualByComparingTo("1")
        assertThat(i.tickSize).isEqualByComparingTo("0.01")
        assertThat(i.volumeStep).isEqualByComparingTo("0.01")
        assertThat(i.volumeMin).isEqualByComparingTo("0.01")
        assertThat(i.expiryMs).isEqualTo(ms("2026-11-07T20:00:00Z"))
        assertThat(i.category).isEqualTo("Climate and Weather")
        assertThat(i.trading).isEqualTo(Tradability.FULL)
        val t = i.event!!
        assertThat(t.series).isEqualTo("KXHIGHNY")
        assertThat(t.event).isEqualTo("KXHIGHNY-26NOV06")
        assertThat(t.occasion).isEqualTo("26NOV06")
        assertThat(t.strikeType).isEqualTo(StrikeType.BETWEEN)
        assertThat(t.floor).isEqualByComparingTo("67")
        assertThat(t.cap).isEqualByComparingTo("68")
        assertThat(t.openAtMs).isEqualTo(ms("2026-11-05T15:00:00Z"))
        assertThat(t.closeAtMs).isEqualTo(ms("2026-11-07T06:00:00Z"))
        assertThat(t.occursAtMs).isEqualTo(ms("2026-11-07T15:00:00Z"))
        assertThat(t.canCloseEarly).isTrue
        assertThat(t.exclusive).isTrue
        assertThat(t.frequency).isEqualTo("1d")
        assertThat(t.title).isEqualTo("67° to 68°")
        assertThat(t.outcome).isNull()
        assertThat(t.fee!!.model).isEqualTo(FeeModel.PROBABILITY)
        assertThat(t.fee!!.takerRate).isEqualByComparingTo("0.07")
        assertThat(t.fee!!.makerRate).isNull()
        assertThat(t.fee!!.rounding).isEqualByComparingTo("0.0001")
    }

    @Test
    fun `a tail reads only the bound it uses`() {
        val e = event("public/events-page-2.json")
        val below = KalshiMapping.terms(e.markets[0], e, null)
        val seats = event("public/events-KXDSENATESEATS.json")
        val atMost = KalshiMapping.terms(seats.markets[0], seats, null)
        val ladder = event("public/events-KXINXU.json")
        val atLeast = KalshiMapping.terms(ladder.markets[0], ladder, series("public/series-KXINXU.json"))

        assertThat(below.strikeType).isEqualTo(StrikeType.LESS)
        assertThat(below.floor).isNull()
        assertThat(below.cap).isEqualByComparingTo("67")
        assertThat(atMost.strikeType).isEqualTo(StrikeType.LESS_OR_EQUAL)
        assertThat(atMost.floor).describedAs("Kalshi's floor 0 on an at-most market is not its bound").isNull()
        assertThat(atMost.cap).isEqualByComparingTo("44")
        assertThat(atLeast.strikeType).isEqualTo(StrikeType.GREATER_OR_EQUAL)
        assertThat(atLeast.floor).isEqualByComparingTo("7225")
        assertThat(atLeast.cap).isNull()
        assertThat(atLeast.exclusive).isFalse
        assertThat(atLeast.frequency).isEqualTo("1h")
    }

    @Test
    fun `an exact count is a between whose floor and cap are the same number, as Kalshi lists it`() {
        val seats = event("public/events-KXDSENATESEATS.json")
        val t = KalshiMapping.terms(seats.markets[1], seats, series("public/series-KXDSENATESEATS.json"))

        assertThat(t.strikeType).isEqualTo(StrikeType.BETWEEN)
        assertThat(t.floor).isEqualByComparingTo("45")
        assertThat(t.cap).isEqualByComparingTo("45")
        assertThat(t.frequency).isEqualTo("one_off")
        assertThat(t.occursAtMs).isNull()
    }

    @Test
    fun `a game winner is custom, carries maker fees, and is disabled on a gateway of another shard`() {
        val e = event("public/events-KXNBAGAME.json")
        val s = series("public/series-KXNBAGAME.json")

        val elsewhere = KalshiMapping.instrument(e.markets[0], e, s, 0)
        val here = KalshiMapping.instrument(e.markets[0], e, s, 3)

        assertThat(elsewhere.trading).isEqualTo(Tradability.DISABLED)
        assertThat(here.trading).isEqualTo(Tradability.FULL)
        assertThat(here.event!!.strikeType).isEqualTo(StrikeType.CUSTOM)
        assertThat(here.event!!.floor).isNull()
        assertThat(here.event!!.occasion).isEqualTo("26NOV05ORLPHX")
        assertThat(here.event!!.fee!!.makerRate).isEqualByComparingTo("0.0175")
        assertThat(here.event!!.frequency).isEqualTo("custom")
    }

    @Test
    fun `a market without a strike asks nothing numeric, and a tapered grid ticks at its finest step`() {
        val e = event("public/events-KXPERSONPRESMAM.json")
        val i = KalshiMapping.instrument(e.markets[0], e, series("public/series-KXPERSONPRESMAM.json"), 0)

        assertThat(i.event!!.strikeType).isEqualTo(StrikeType.NONE)
        assertThat(i.tickSize).isEqualByComparingTo("0.001")
        assertThat(i.event!!.occasion).isEqualTo("45")
    }

    @Test
    fun `a finalized market is disabled and carries its outcome`() {
        val e = event("public/events-KXSP500WHEN.json")
        val i = KalshiMapping.instrument(e.markets[0], e, null, 0)

        assertThat(i.trading).isEqualTo(Tradability.DISABLED)
        assertThat(i.event!!.outcome).isEqualByComparingTo("0")
        assertThat(i.category).isNull()
        assertThat(i.event!!.fee).isNull()
        assertThat(i.event!!.frequency).isNull()
    }

    @Test
    fun `an event ticker that does not start with its series has no occasion`() {
        val e = event("public/events-page-2.json")
        val odd = e.copy(seriesTicker = "OTHER")

        assertThat(KalshiMapping.terms(e.markets[0], odd, null).occasion).isNull()
        assertThat(KalshiMapping.tick(e.markets[0].copy(priceRanges = emptyList()))).isEqualByComparingTo("0.01")
    }
}
