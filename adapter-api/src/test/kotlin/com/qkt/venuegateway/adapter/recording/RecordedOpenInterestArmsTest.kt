package com.qkt.venuegateway.adapter.recording

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RecordedOpenInterestArmsTest {
    @TempDir lateinit var dir: Path
    private val far = { 10_000_000_000L }

    private fun recorded(rows: String) {
        Files.createDirectories(dir.resolve("open-interest/X"))
        Files.writeString(dir.resolve("open-interest/X/1970-01-01.csv"), "time,open_interest\n$rows")
    }

    private fun read(
        from: Long,
        to: Long,
    ) = RecordedOpenInterest(dir, far, 0).read("X", from, to) { null }

    @Test
    fun `a read serves the recorded figures inside its range, oldest first, each time once`() {
        recorded("3000,3.5\n1000,1.5\n2000,2.5\n2000,9\n")

        assertThat(read(1_500, 2_500).map { it.timeMs }).containsExactly(2_000L)
        assertThat(read(0, 5_000).map { it.timeMs }).containsExactly(1_000L, 2_000L, 3_000L)
        assertThat(read(0, 5_000).first { it.timeMs == 2_000L }.openInterest.toPlainString()).isEqualTo("2.5")
        assertThat(read(2_000, 1_000)).isEmpty()
    }

    @Test
    fun `a recorded line that is not a time and a figure fails naming its file and line`() {
        recorded("abc,1\n")
        assertThatThrownBy { read(0, 5_000) }.hasMessageContaining("line 2").hasMessageContaining("abc,1")
        recorded("1000,many\n")
        assertThatThrownBy { read(0, 5_000) }.hasMessageContaining("expected time,open_interest")
        recorded("1000,1,2\n")
        assertThatThrownBy { read(0, 5_000) }.hasMessageContaining("1000,1,2")
        recorded("1000\n")
        assertThatThrownBy { read(0, 5_000) }.hasMessageContaining("line 2")
    }
}
