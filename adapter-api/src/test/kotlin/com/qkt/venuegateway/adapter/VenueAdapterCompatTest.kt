package com.qkt.venuegateway.adapter

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A plugin compiled against an older Kotlin reaches an interface default through its `DefaultImpls` class, which the
 * gateway keeps for it: the defaults answer the same there as they do to a plugin compiled today.
 */
class VenueAdapterCompatTest {
    private val receiver: VenueAdapter =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(VenueAdapter::class.java),
        ) { _, _, _ -> null } as VenueAdapter

    private fun legacy(
        name: String,
        vararg args: Any,
    ): Any? {
        val impls = Class.forName("${VenueAdapter::class.java.name}\$DefaultImpls")
        val method = impls.declaredMethods.first { it.name == name }
        return try {
            method.invoke(null, receiver, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `an old plugin gets the declared capabilities, no retired contract and the listing refresh by default`() {
        assertThat(legacy("getCapabilities") as Set<*>)
            .containsExactlyInAnyOrder(Capability.BARS, Capability.QUOTES, Capability.SETTLEMENTS)
        assertThat(legacy("instrument", "BTC")).isNull()
        assertThat(legacy("getListingRefreshMs")).isEqualTo(60_000L)
    }

    @Test
    fun `an old plugin that implements no optional history has each refused as unsupported, naming it`() {
        val refusals =
            mapOf<String, () -> Any?>(
                "funding" to { legacy("funding", 0L, 1L) },
                "funding rates" to { legacy("fundingRates", "X", 0L, 1L) },
                "mark prices" to { legacy("marks", "X", 60_000L, 0L, 1L) },
                "open interest" to { legacy("openInterest", "X", 0L, 1L) },
                "trades" to { legacy("trades", "X", 0L, 1L, 10) },
                "liquidations" to { legacy("liquidations", "X", 0L, 1L) },
                "depth" to { legacy("depth", "X", 0L, 1L) },
            )

        refusals.forEach { (what, call) ->
            assertThatThrownBy { call() }
                .isInstanceOf(VenueUnsupportedException::class.java)
                .hasMessage("the adapter does not serve $what")
        }
    }
}
