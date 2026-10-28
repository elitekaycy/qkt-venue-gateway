package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.TradeMode

/** Where an Alpaca account lives: its trading REST host and trade-update socket, and whether it is real money. */
enum class AlpacaEnvironment(
    val tradingUrl: String,
    val streamUrl: String,
    val mode: TradeMode,
) {
    PAPER("https://paper-api.alpaca.markets", "wss://paper-api.alpaca.markets/stream", TradeMode.DEMO),
    LIVE("https://api.alpaca.markets", "wss://api.alpaca.markets/stream", TradeMode.REAL),
}

/**
 * The Alpaca adapter's settings. `environment` (`paper` or `live`) has no default, since one of them is real money.
 * `feed` (`iex`, the free one, or `sip`, default `iex`) is the stock data feed quotes and bars come from.
 * `extended_hours` (default `false`): when `true`, every limit DAY or GTC order on a share is sent with Alpaca's
 * `extended_hours`, so it may fill pre-market, after hours and overnight; VGP orders carry no such flag, so it is the
 * account's choice. `asset_classes` (`us_equity`, `crypto` or both, comma-separated, default `us_equity`) are what is
 * listed: coin pairs are opt-in, because qkt keeps a coin buy's order working for the sliver Alpaca takes as its fee in
 * the coin (qkt#1515).
 * `fractional` (default `false`): when `true`, a share Alpaca can split is listed in steps of a billionth of a share;
 * Alpaca takes a fractional quantity on DAY orders only and never short, so the default lists whole shares.
 */
data class AlpacaSettings(
    val environment: AlpacaEnvironment,
    val feed: String = "iex",
    val extendedHours: Boolean = false,
    val assetClasses: Set<String> = DEFAULT_CLASSES,
    val fractional: Boolean = false,
    val dataUrl: String = "https://data.alpaca.markets",
    val dataStreamUrl: String = "wss://stream.data.alpaca.markets",
) {
    /** Reads and checks the settings, refusing a bad one by its key (the context's typed readers name it). */
    companion object {
        const val EQUITY = "us_equity"
        const val CRYPTO = "crypto"
        private val CLASSES = setOf(EQUITY, CRYPTO)
        private val DEFAULT_CLASSES = setOf(EQUITY)

        private val FEEDS = setOf("iex", "sip")

        fun of(context: AdapterContext): AlpacaSettings {
            val feed = context.text("feed", "iex")
            require(feed in FEEDS) { "setting feed must be one of ${FEEDS.joinToString()}: $feed" }
            val classes =
                context
                    .text(
                        "asset_classes",
                        DEFAULT_CLASSES.joinToString(","),
                    ).split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            require(classes.isNotEmpty() && CLASSES.containsAll(classes)) {
                "setting asset_classes must be of ${CLASSES.joinToString()}: $classes"
            }
            return AlpacaSettings(
                context.enum("environment", AlpacaEnvironment.entries.toTypedArray()),
                feed,
                context.bool("extended_hours", false),
                classes,
                context.bool("fractional", false),
            )
        }
    }
}
