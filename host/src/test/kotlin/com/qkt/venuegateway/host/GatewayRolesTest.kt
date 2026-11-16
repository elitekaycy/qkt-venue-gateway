package com.qkt.venuegateway.host

import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.server.Role
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GatewayRolesTest {
    @Test
    fun `a role is the one whose token the bearer header carries, and a header of any other shape carries none`(
        @TempDir dir: Path,
    ) {
        val gateway =
            Gateway(
                FakeAdapter(),
                Journal.open(dir.resolve("j.db")),
                InstrumentShelf.open(dir.resolve("i.db")) { 1L },
                mapOf(Role.TRADER to "t-token", Role.GUARDIAN to "g-token"),
            ) { 1L }

        assertThat(gateway.roleOf("Bearer t-token")).isEqualTo(Role.TRADER)
        assertThat(gateway.roleOf("Bearer g-token")).isEqualTo(Role.GUARDIAN)
        assertThat(gateway.roleOf(null)).isNull()
        assertThat(gateway.roleOf("t-token")).describedAs("no Bearer prefix").isNull()
        assertThat(gateway.roleOf("Basic t-token")).isNull()
        assertThat(gateway.roleOf("Bearer nope")).isNull()
        assertThat(gateway.roleOf("Bearer ")).isNull()
    }
}
