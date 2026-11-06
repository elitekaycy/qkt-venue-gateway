package com.qkt.venuegateway.adapter.recording

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DayFilesMergeTest {
    @TempDir lateinit var dir: Path
    private val day = 86_400_000L
    private val noon = LocalDate.parse("2026-10-05").toEpochDay() * day + day / 2

    private fun text(name: String) = Files.readString(dir.resolve(name))

    @Test
    fun `a merge keeps what a day file holds, adds what is new and lets the new row win at the same time`() {
        val series = DayFiles(dir.resolve("headed"), "time,x\n")
        series.append(listOf(noon to "$noon,old", noon + 1 to "${noon + 1},kept"))

        series.merge(mapOf(noon to "$noon,new", noon + 2 to "${noon + 2},added", noon + day to "${noon + day},next"))

        assertThat(text("headed/2026-10-05.csv")).isEqualTo("time,x\n$noon,new\n${noon + 1},kept\n${noon + 2},added\n")
        assertThat(text("headed/2026-10-06.csv")).isEqualTo("time,x\n${noon + day},next\n")
    }

    @Test
    fun `a merge into a series with no header writes none, and a day file of rows only is read as such`() {
        val series = DayFiles(dir.resolve("bare"), null)

        series.merge(mapOf(noon to "$noon,1"))
        series.merge(mapOf(noon + 1 to "${noon + 1},2"))

        assertThat(text("bare/2026-10-05.csv")).isEqualTo("$noon,1\n${noon + 1},2\n")
        assertThat(series.lastTime()).isEqualTo(noon + 1)
    }

    @Test
    fun `the newest time is the newest day file's, or the day before's when the newest holds no row yet`() {
        val series = DayFiles(dir.resolve("headed"), "time,x\n")
        series.append(listOf(noon to "$noon,1"))
        Files.writeString(dir.resolve("headed/2026-10-06.csv"), "time,x\n")

        assertThat(series.lastTime()).isEqualTo(noon)
        assertThat(DayFiles(dir.resolve("nothing"), null).lastTime()).isNull()
    }
}
