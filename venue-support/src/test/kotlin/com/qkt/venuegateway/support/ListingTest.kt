package com.qkt.venuegateway.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ListingTest {
    private var now = 0L
    private var listed = listOf("A")
    private var reads = 0

    private fun listing(missRereadMs: Long) =
        Listing({
            reads++
            listed
        }, { it }, { now }, 100, missRereadMs)

    @Test
    fun `the listing is read once, then again only once it is refresh old`() {
        val listing = listing(0)

        assertThat(listing.all().keys).containsExactly("A")
        now = 99
        listed = listOf("A", "B")
        assertThat(listing.all().keys).containsExactly("A")
        now = 100
        assertThat(listing.all().keys).containsExactly("A", "B")
        assertThat(reads).isEqualTo(2)
    }

    @Test
    fun `a miss reads again when the listing is old enough, and a cached lookup never does`() {
        val every = listing(0)
        val minute = listing(60)
        every.all()
        minute.all()
        listed = listOf("A", "B")

        assertThat(every.cached("B")).isNull()
        assertThat(every.find("B")).isEqualTo("B")
        assertThat(minute.find("B")).isNull()
        now = 60
        assertThat(minute.find("B")).isEqualTo("B")
        assertThat(minute.find("C")).isNull()
        assertThat(reads).isEqualTo(4)
    }
}
