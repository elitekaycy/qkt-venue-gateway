package com.qkt.venuegateway

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MainArgsTest {
    @Test
    fun `the gateway takes no arguments, and says it is configured by environment variables`() {
        assertThatThrownBy { main(arrayOf("--port", "8080")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("takes no arguments")
            .hasMessageContaining("GATEWAY_")
    }
}
