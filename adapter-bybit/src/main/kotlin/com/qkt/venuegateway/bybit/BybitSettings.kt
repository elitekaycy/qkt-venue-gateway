package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.TradeMode

/** Where a Bybit account lives: its REST host, its socket host, and whether it is real money. */
enum class BybitEnvironment(
    val restUrl: String,
    val socketUrl: String,
    val mode: TradeMode,
) {
    TESTNET("https://api-testnet.bybit.com", "wss://stream-testnet.bybit.com", TradeMode.DEMO),
    MAINNET("https://api.bybit.com", "wss://stream.bybit.com", TradeMode.REAL),
}

/**
 * The Bybit adapter's settings. `environment` (`testnet` or `mainnet`) has no default, since one of them is
 * real money; nor has `category` (`linear`: USDT or USDC perpetuals and dated futures; `spot`), since one
 * gateway trades one category of one account. `currency` (default `USDT`) is the coin a linear contract
 * settles in, or the coin a spot pair is quoted in: only that coin's instruments are listed and traded.
 * `stop_trigger` (`last_price`, `mark_price` or `index_price`, default `last_price`) is the price a linear stop
 * fires on; spot stops fire on the last price. [retention] is how long the gateway keeps what it records of
 * Bybit's market, which Bybit keeps no history of ([BybitRetention]).
 */
data class BybitSettings(
    val environment: BybitEnvironment,
    val category: String,
    val currency: String,
    val stopTrigger: String,
    val retention: BybitRetention = BybitRetention(),
) {
    /** Whether the category trades contracts (linear) rather than coins (spot). */
    val contracts get() = category == LINEAR

    /**
     * What the category serves beyond orders: every category its bars, quotes, tape and depth; contracts also
     * funding, funding rates, marks, open interest and liquidations, which spot has none of.
     */
    val capabilities: Set<Capability>
        get() =
            setOf(Capability.BARS, Capability.QUOTES, Capability.TRADES, Capability.DEPTH) +
                if (contracts) CONTRACTS else emptySet()

    /** Reads and checks the settings, refusing a bad one by its key. */
    companion object {
        const val LINEAR = "linear"
        const val SPOT = "spot"
        private val CONTRACTS =
            setOf(
                Capability.FUNDING,
                Capability.FUNDING_RATES,
                Capability.MARK_PRICES,
                Capability.OPEN_INTEREST,
                Capability.LIQUIDATIONS,
            )
        private val TRIGGERS =
            mapOf(
                "last_price" to "LastPrice",
                "mark_price" to "MarkPrice",
                "index_price" to "IndexPrice",
            )
        private val CURRENCIES = mapOf(LINEAR to setOf("USDT", "USDC"), SPOT to setOf("USDT", "USDC"))

        fun of(settings: Map<String, String>): BybitSettings {
            val environment = settings["environment"] ?: error("setting environment is required: testnet or mainnet")
            val category = settings["category"] ?: error("setting category is required: linear or spot")
            val currencies = CURRENCIES[category] ?: error("bybit category $category is not linear or spot")
            val currency = settings["currency"] ?: "USDT"
            require(currency in currencies) { "bybit currency $currency is not one of $currencies for $category" }
            val trigger = settings["stop_trigger"] ?: "last_price"
            val stopTrigger = TRIGGERS[trigger] ?: error("bybit stop_trigger $trigger is not one of ${TRIGGERS.keys}")
            require(category == LINEAR || trigger == "last_price") {
                "bybit spot stops fire on the last price only: stop_trigger $trigger"
            }
            return BybitSettings(
                BybitEnvironment.entries.firstOrNull { it.name.equals(environment, ignoreCase = true) }
                    ?: error("bybit environment $environment is not testnet or mainnet"),
                category,
                currency,
                stopTrigger,
                BybitRetention.of(settings),
            )
        }
    }
}
