package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.Tradability
import com.qkt.venuegateway.support.JsonReads.bool
import com.qkt.venuegateway.support.JsonReads.need
import com.qkt.venuegateway.support.JsonReads.needDecimal
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject

/**
 * Alpaca assets as instruments. A share's code is its symbol (`AAPL`, `BRK.B`), a coin pair's is its symbol
 * without the slash (`BTC/USD` is `BTCUSD`, the form Alpaca's positions use). Shares are whole: a lot of one share,
 * priced in cents (Alpaca refuses a sub-penny price on a share at or above $1), unless the account trades fractions
 * ([fractional]) and Alpaca can split the share (`fractionable`): it is then sized in billionths (Alpaca: `qty must be
 * > 1e-9`).
 * A share Alpaca will not lend is long-only; a coin pair is always long-only (Alpaca sells no coin short); an asset
 * Alpaca lists but does not trade is disabled. [category] is the share's exchange, or `crypto`.
 */
object AlpacaInstruments {
    private val SHARE_TICK = BigDecimal("0.01")
    private val SHARE_FRACTION = BigDecimal("0.000000001")

    /** The gateway code of Alpaca symbol [symbol]. */
    fun code(symbol: String): String = symbol.replace("/", "")

    fun instrument(
        asset: JsonObject,
        fractional: Boolean = false,
    ): Instrument =
        when (val assetClass = asset.need("class")) {
            AlpacaSettings.EQUITY ->
                Instrument(
                    code = code(asset.need("symbol")),
                    kind = InstrumentKind.EQUITY,
                    currency = "USD",
                    contractSize = BigDecimal.ONE,
                    tickSize = SHARE_TICK,
                    volumeStep =
                        if (fractional &&
                            asset.bool("fractionable") == true
                        ) {
                            SHARE_FRACTION
                        } else {
                            BigDecimal.ONE
                        },
                    volumeMin =
                        if (fractional &&
                            asset.bool("fractionable") == true
                        ) {
                            SHARE_FRACTION
                        } else {
                            BigDecimal.ONE
                        },
                    category = asset.need("exchange"),
                    trading =
                        when {
                            asset.bool("tradable") != true -> Tradability.DISABLED
                            asset.bool("shortable") != true -> Tradability.LONG_ONLY
                            else -> Tradability.FULL
                        },
                )
            AlpacaSettings.CRYPTO -> {
                val symbol = asset.need("symbol")
                Instrument(
                    code = code(symbol),
                    kind = InstrumentKind.SPOT,
                    currency = symbol.substringAfter('/'),
                    contractSize = BigDecimal.ONE,
                    tickSize = asset.needDecimal("price_increment"),
                    volumeStep = asset.needDecimal("min_trade_increment"),
                    volumeMin = asset.needDecimal("min_order_size"),
                    category = AlpacaSettings.CRYPTO,
                    trading = if (asset.bool("tradable") == true) Tradability.LONG_ONLY else Tradability.DISABLED,
                )
            }
            else -> error("alpaca asset class $assetClass is neither us_equity nor crypto")
        }
}
