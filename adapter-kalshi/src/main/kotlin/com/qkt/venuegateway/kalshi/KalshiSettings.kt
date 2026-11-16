package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.TradeMode

/** Where a Kalshi account lives: its REST base, its socket, and whether it is real money. */
enum class KalshiEnvironment(
    val restUrl: String,
    val socketUrl: String,
    val mode: TradeMode,
) {
    DEMO(
        "https://external-api.demo.kalshi.co/trade-api/v2",
        "wss://external-api-ws.demo.kalshi.co/trade-api/ws/v2",
        TradeMode.DEMO,
    ),
    PRODUCTION(
        "https://external-api.kalshi.com/trade-api/v2",
        "wss://external-api-ws.kalshi.com/trade-api/ws/v2",
        TradeMode.REAL,
    ),
}

/**
 * The Kalshi adapter's settings. `environment` (`demo` or `production`) has no default, since one of them is real
 * money. `shard` (default 0) is the exchange index whose capital this gateway trades: Kalshi keeps a balance per
 * shard (0 default, 1 combos, 2 crypto and commodities, 3 tennis, baseball and basketball, as production named them
 * on 2026-11-05), so one gateway trades the markets of one shard and lists every other market `disabled`. `series`
 * names the Kalshi series the gateway lists and trades, separated by commas (`KXINXU,KXBTCD`), and has no default:
 * the whole catalogue (`*`, held here as an empty [series]) is over 100,000 markets and many minutes of reading, so
 * it is only ever asked for by name.
 */
data class KalshiSettings(
    val environment: KalshiEnvironment,
    val shard: Int,
    val series: List<String>,
) {
    /** Reads and checks the settings, refusing a bad one by its key. */
    companion object {
        private const val SHARDS = 4
        private const val EVERY = "*"
        private val TICKER = Regex("[A-Za-z0-9._-]+")

        fun of(context: AdapterContext): KalshiSettings {
            val environment = context.enum("environment", KalshiEnvironment.entries.toTypedArray())
            val shard = context.int("shard", 0)
            require(shard in 0 until SHARDS) { "setting shard must be a kalshi exchange index, 0 to 3: $shard" }
            return KalshiSettings(environment, shard, series(context.required("series")))
        }

        private fun series(raw: String): List<String> {
            val named =
                raw
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            if (named == listOf(EVERY)) return emptyList()
            val bad = named.firstOrNull { !TICKER.matches(it) }
            require(named.isNotEmpty() && bad == null) {
                "setting series must be kalshi series tickers separated by commas, or * for every series: $raw"
            }
            return named
        }
    }
}
