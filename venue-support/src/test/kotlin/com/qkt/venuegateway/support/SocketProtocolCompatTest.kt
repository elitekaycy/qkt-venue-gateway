package com.qkt.venuegateway.support

import java.lang.reflect.Proxy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A protocol compiled against an older Kotlin gets the hooks it does not write through `DefaultImpls`: all no-ops. */
class SocketProtocolCompatTest {
    private val receiver: SocketProtocol =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SocketProtocol::class.java),
        ) { _, _, _ -> null } as SocketProtocol
    private val impls = Class.forName("${SocketProtocol::class.java.name}\$DefaultImpls")

    @Test
    fun `an old protocol that writes only its frames ignores opening, up and a drop`() {
        val sent = mutableListOf<String>()
        val link =
            SocketLink {
                sent += it
                true
            }

        impls.declaredMethods.first { it.name == "opening" }.invoke(null, receiver, link)
        impls.declaredMethods.first { it.name == "up" }.invoke(null, receiver)
        impls.declaredMethods.first { it.name == "dropped" }.invoke(null, receiver, "closed", true)

        assertThat(sent).isEmpty()
    }
}
