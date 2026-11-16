package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.InstrumentFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Which queries are put to the venue itself: one naming a series or an event, and never one asking for changes. */
class CatalogSearchTest {
    @Test
    fun `a query naming a series or an event is put to the venue`() {
        assertThat(CatalogQuery(series = "KXS").search()).isEqualTo(InstrumentFilter(series = "KXS"))
        assertThat(CatalogQuery(event = "KXS-E1").search()).isEqualTo(InstrumentFilter(event = "KXS-E1"))
        assertThat(CatalogQuery(series = "KXS", event = "KXS-E1", status = "open").search())
            .isEqualTo(InstrumentFilter("KXS", "KXS-E1"))
    }

    @Test
    fun `a query naming neither, or asking for changes, is answered from the gateway's own reads`() {
        assertThat(CatalogQuery(kind = "event", category = "Financials").search()).isNull()
        assertThat(CatalogQuery(series = "KXS", updatedSince = 5).search()).isNull()
        assertThat(CatalogQuery(event = "KXS-E1", updatedSince = 5).search()).isNull()
    }
}
