package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.TradeMode

/** Where a Deribit account lives: its JSON-RPC WebSocket, its HTTPS host, and whether it is real money. */
enum class DeribitEnvironment(
    val socketUrl: String,
    val httpUrl: String,
    val mode: TradeMode,
) {
    TESTNET("wss://test.deribit.com/ws/api/v2", "https://test.deribit.com", TradeMode.DEMO),
    MAINNET("wss://www.deribit.com/ws/api/v2", "https://www.deribit.com", TradeMode.REAL),
}

/**
 * The Deribit adapter's settings: `environment` (`testnet` or `mainnet`, never defaulted, since one
 * of them is real money), `currency` (`USDC`: linear contracts, whose amounts are coins; coin-margined
 * contracts quote amounts in dollars and are refused), and `stop_trigger` (`last_price`, `mark_price`
 * or `index_price`, default `last_price`): the price Deribit watches to fire a stop.
 */
data class DeribitSettings(
    val environment: DeribitEnvironment,
    val currency: String,
    val stopTrigger: String,
) {
    companion object {
        private val TRIGGERS = setOf("last_price", "mark_price", "index_price")

        fun of(settings: Map<String, String>): DeribitSettings {
            val environment =
                settings["environment"] ?: error("adapter.settings.environment is required: testnet or mainnet")
            val currency = settings["currency"] ?: "USDC"
            val trigger = settings["stop_trigger"] ?: "last_price"
            require(
                currency == "USDC",
            ) { "deribit currency $currency is not supported: only USDC (linear) contracts are" }
            require(trigger in TRIGGERS) { "deribit stop_trigger $trigger is not one of $TRIGGERS" }
            return DeribitSettings(
                DeribitEnvironment.entries.firstOrNull { it.name.equals(environment, ignoreCase = true) }
                    ?: error("deribit environment $environment is not testnet or mainnet"),
                currency,
                trigger,
            )
        }
    }
}
