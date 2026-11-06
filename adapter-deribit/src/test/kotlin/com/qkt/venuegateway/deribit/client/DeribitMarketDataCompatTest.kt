package com.qkt.venuegateway.deribit.client

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A market compiled against an older Kotlin reaches the reads it does not write through `DefaultImpls`: refused. */
class DeribitMarketDataCompatTest {
    private val receiver: DeribitMarketData =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(DeribitMarketData::class.java),
        ) { _, _, _ -> null } as DeribitMarketData

    private fun legacy(
        name: String,
        vararg args: Any,
    ): Any? {
        val impls = Class.forName("${DeribitMarketData::class.java.name}\$DefaultImpls")
        return try {
            impls.declaredMethods.first { it.name == name }.invoke(null, receiver, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun `an old market that writes none of the optional reads has each refused by what it does not read`() {
        val refusals =
            mapOf<String, () -> Any?>(
                "order books" to { legacy("orderBook", "X", 10) },
                "funding rates" to { legacy("fundingRates", "X", 0L, 1L) },
                "trades" to { legacy("edgeTrade", "X", 0L, 1L, true) },
                "the trade tape" to { legacy("tape", "X", 0L, 1L, 5) },
            )

        refusals.forEach { (what, call) ->
            assertThatThrownBy { call() }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining(what)
        }
        assertThatThrownBy { legacy("tradesFrom", "X", 0L, 1L, 5) }.hasMessageContaining("trades")
        assertThat(receiver).isNotNull
    }
}
