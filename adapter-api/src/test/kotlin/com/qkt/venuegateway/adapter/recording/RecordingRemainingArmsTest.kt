package com.qkt.venuegateway.adapter.recording

import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueLevel
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RecordingRemainingArmsTest {
    @TempDir lateinit var dir: Path
    private val day = 86_400_000L

    @Test
    fun `a prune that finds nothing past the retention deletes nothing and keeps today's file`() {
        val now = 100 * day + 5
        val recording = Recording(dir.resolve("trades"), null, 1, { now })
        recording.series("X").append(listOf(now to "$now,a"))

        assertThat(recording.prune()).isZero
        assertThat(recording.series("X").days()).hasSize(1)
    }

    @Test
    fun `a recorded book outside the range read is not served`() {
        val depth = RecordedDepth(dir, true, { 10_000L }, 0)
        val book =
            VenueDepth(
                10_000,
                listOf(VenueLevel(BigDecimal("1"), BigDecimal("2"))),
                listOf(VenueLevel(BigDecimal("3"), BigDecimal("4"))),
            )

        assertThat(depth.read("X", 0, 10_000) { book }).containsExactly(book)
        assertThat(depth.read("X", 0, 5_000) { book }).isEmpty()
        assertThat(Files.exists(dir.resolve("depth/X"))).isTrue
    }
}
