package com.qkt.venuegateway.adapter

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AdapterContextTest {
    private enum class Mode { FAST, SLOW }

    private fun context(
        vararg settings: Pair<String, String>,
        credentials: Credentials? = null,
    ) = AdapterContext(mapOf(*settings), { 0L }, Path.of("."), credentials)

    @Test
    fun `a required setting is read, and an absent or blank one is refused by name`() {
        val c = context("key" to "value", "blank" to " ")

        assertThat(c.required("key")).isEqualTo("value")
        assertThatThrownBy { c.required("absent") }.hasMessage("setting absent is required")
        assertThatThrownBy { c.required("blank") }.hasMessage("setting blank is required")
        assertThat(c.text("key", "d")).isEqualTo("value")
        assertThat(c.text("blank", "d")).isEqualTo("d")
    }

    @Test
    fun `an enum setting falls back to its default, and without one it is required`() {
        val c = context("side" to " slow ")

        assertThat(c.enum("side", Mode.entries.toTypedArray())).isEqualTo(Mode.SLOW)
        assertThat(c.enum("absent", Mode.entries.toTypedArray(), Mode.FAST)).isEqualTo(Mode.FAST)
        assertThatThrownBy { c.enum("absent", Mode.entries.toTypedArray()) }.hasMessage("setting absent is required")
        assertThat(context("side" to " ").enum("side", Mode.entries.toTypedArray(), Mode.FAST)).isEqualTo(Mode.FAST)
        assertThat(context("n" to "").int("n", 4)).isEqualTo(4)
    }

    @Test
    fun `numbers, decimals and flags parse trimmed, default when absent, and refuse what they cannot read`() {
        val c = context("n" to " 12 ", "big" to "9000000000", "fee" to "x", "flag" to "yes", "off" to "false")

        assertThat(c.long("big")).isEqualTo(9_000_000_000L)
        assertThat(c.long("absent", 3L)).isEqualTo(3L)
        assertThat(c.int("n")).isEqualTo(12)
        assertThat(c.decimal("absent", java.math.BigDecimal.TEN)).isEqualByComparingTo("10")
        assertThat(c.bool("off")).isFalse
        assertThat(c.bool("absent", true)).isTrue
        assertThatThrownBy { c.long("fee") }.hasMessage("setting fee must be a whole number: x")
        assertThatThrownBy { c.decimal("fee") }.hasMessage("setting fee must be a decimal: x")
        assertThatThrownBy { c.bool("flag") }.hasMessage("setting flag must be true or false: yes")
        assertThatThrownBy { c.int("absent") }.hasMessage("setting absent is required")
    }

    @Test
    fun `credentials are handed over when given, required by name when not, and never print their secret`() {
        val login = Credentials("client-1", "s3cret")

        assertThat(context(credentials = login).requiredCredentials()).isSameAs(login)
        assertThatThrownBy { context().requiredCredentials() }.hasMessage("credentials are required")
        assertThat(login.toString()).isEqualTo("Credentials(login=client-1, secret=***)").doesNotContain("s3cret")
    }

    @Test
    fun `a negative recording retention is refused, and zero keeps everything`() {
        assertThatThrownBy { AdapterContext(emptyMap(), { 0L }, Path.of("."), recordingRetentionDays = -1) }
            .hasMessage("recording retention must be 0 or more days: -1")
        assertThat(AdapterContext(emptyMap(), { 0L }, Path.of(".")).recordingRetentionDays).isZero
    }

    @Test
    fun `the scheduler's threads are daemons named by their prefix and count`() {
        val factory = daemon("venue")
        val first = factory.newThread {}
        val second = factory.newThread {}

        assertThat(listOf(first.name, second.name)).containsExactly("venue-1", "venue-2")
        assertThat(first.isDaemon && second.isDaemon).isTrue
    }
}
