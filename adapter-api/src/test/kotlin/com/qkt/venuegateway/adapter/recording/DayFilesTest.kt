package com.qkt.venuegateway.adapter.recording

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DayFilesTest {
    @TempDir lateinit var dir: Path
    private val day = 86_400_000L
    private val noon = LocalDate.parse("2026-10-05").toEpochDay() * day + day / 2

    private fun text(name: String) = Files.readString(dir.resolve(name))

    @Test
    fun `a headed series starts each day's file with its header, a bare one never does`() {
        DayFiles(dir.resolve("headed"), "time,x\n").append(listOf(noon to "$noon,1", noon + day to "${noon + day},2"))
        DayFiles(dir.resolve("bare"), null).append(listOf(noon to "$noon,1"))
        DayFiles(dir.resolve("bare"), null).append(listOf(noon + 1 to "${noon + 1},2"))

        assertThat(text("headed/2026-10-05.csv")).isEqualTo("time,x\n$noon,1\n")
        assertThat(text("headed/2026-10-06.csv")).isEqualTo("time,x\n${noon + day},2\n")
        assertThat(text("bare/2026-10-05.csv")).isEqualTo("$noon,1\n${noon + 1},2\n")
    }

    @Test
    fun `rows skip the header only where there is one, and number every line as the file does`() {
        val headed = DayFiles(dir.resolve("headed"), "time,x\n").also { it.append(listOf(noon to "$noon,1")) }
        val bare = DayFiles(dir.resolve("bare"), null).also { it.append(listOf(noon to "$noon,1")) }

        assertThat(headed.rows(headed.days().single())).containsExactly(IndexedValue(2, "$noon,1"))
        assertThat(bare.rows(bare.days().single())).containsExactly(IndexedValue(1, "$noon,1"))
        assertThat(bare.lastTime()).isEqualTo(noon)
    }

    @Test
    fun `a last line a crash cut short is never read and is cut off before the next append`() {
        val series = DayFiles(dir.resolve("s"), null)
        series.append(listOf(noon to "$noon,1"))
        Files.writeString(dir.resolve("s/2026-10-05.csv"), "${noon + 1},to", StandardOpenOption.APPEND)

        assertThat(series.rows(series.days().single()).map { it.value }).containsExactly("$noon,1")
        series.append(listOf(noon + 2 to "${noon + 2},3"))

        assertThat(text("s/2026-10-05.csv")).isEqualTo("$noon,1\n${noon + 2},3\n")
    }

    @Test
    fun `a merge keeps one row a time, rewrites the day whole, and keeps the header`() {
        val series = DayFiles(dir.resolve("s"), "time,x\n")
        series.append(listOf(noon to "$noon,1"))

        series.merge(mapOf(noon to "$noon,1", noon - 1 to "${noon - 1},0"))

        assertThat(text("s/2026-10-05.csv")).isEqualTo("time,x\n${noon - 1},0\n$noon,1\n")
    }

    @Test
    fun `days far outside the calendar are held to the years a file can name`() {
        assertThat(DayFiles.day(Long.MAX_VALUE)).isEqualTo(LocalDate.parse("9999-12-31"))
        assertThat(DayFiles.day(Long.MIN_VALUE)).isEqualTo(LocalDate.parse("0000-01-01"))
        assertThat(DayFiles(dir.resolve("none"), null).days()).isEmpty()
    }

    @Test
    fun `a recording prunes every code's days before its window at most once a UTC day, and 0 keeps all`() {
        var now = noon
        val kept = Recording(dir.resolve("r"), null, 2, { now })
        listOf("A", "B").forEach { code ->
            kept.series(code).append(listOf(noon - 3 * day to "x", noon - 2 * day to "y", noon to "z"))
        }

        assertThat(Recording(dir.resolve("r"), null, 0) { now }.prune()).isZero
        assertThat(kept.prune()).isEqualTo(2)
        assertThat(
            kept.series("A").days().map { it.fileName.toString() },
        ).containsExactly("2026-10-03.csv", "2026-10-05.csv")
        kept.series("A").append(listOf(noon - 3 * day to "x"))
        assertThat(kept.prune()).isZero
        now += day
        assertThat(kept.prune()).isEqualTo(3)
    }
}
