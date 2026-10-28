package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.alpaca.client.AlpacaTrading
import com.qkt.venuegateway.support.JsonReads.need
import com.qkt.venuegateway.support.Listing
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject

/** One listed instrument, with the symbol Alpaca names it by (`BTC/USD` for code `BTCUSD`) and whether it is a coin pair. */
data class AlpacaListed(
    val instrument: Instrument,
    val symbol: String,
    val crypto: Boolean,
)

/**
 * The account's tradable universe, by code, on the shared [Listing] cache. The coin pairs (a short list) are read
 * whole; a share is looked up by itself when an order or a quote names it, since the whole share list is fourteen
 * thousand assets (6.5 MB, from 2 s to 166 s measured), read only when the host lists instruments. Each is reread
 * after [refreshMs] (a share can stop trading or lending).
 */
class AlpacaListing(
    private val trading: AlpacaTrading,
    private val classes: Set<String>,
    private val clock: () -> Long,
    private val refreshMs: Long = 600_000,
    private val fractional: Boolean = false,
) {
    private class Read(
        val listed: AlpacaListed?,
        val atMs: Long,
    )

    private val pairs = Listing({ read(AlpacaSettings.CRYPTO) }, { it.instrument.code }, clock, refreshMs)
    private val everyShare = Listing({ read(AlpacaSettings.EQUITY) }, { it.instrument.code }, clock, refreshMs)
    private val shares = ConcurrentHashMap<String, Read>()

    /** Every listed instrument: the whole share list and the coin pairs, each read at most once every [refreshMs]. */
    fun all(): List<AlpacaListed> {
        val now = clock()
        val equities = everyShare.all().values.onEach { shares[it.instrument.code] = Read(it, now) }
        return equities + pairs.all().values
    }

    /** [code]'s listing, or a refusal naming it: an order for an unlisted code never reaches Alpaca. */
    fun require(code: String): AlpacaListed = find(code) ?: throw VenueRefusedException("alpaca lists no $code")

    fun find(code: String): AlpacaListed? {
        pairs.cached(code)?.let { return it }
        if (AlpacaSettings.EQUITY !in classes) return null
        val now = clock()
        shares[code]?.takeIf { now - it.atMs < refreshMs }?.let { return it.listed }
        val asset =
            AlpacaErrors.venue { trading.asset(code) }?.takeIf {
                it.need("class") == AlpacaSettings.EQUITY && it.need("status") == "active"
            }
        return asset?.let(::listed).also { shares[code] = Read(it, now) }
    }

    private fun read(assetClass: String): List<AlpacaListed> =
        if (assetClass in classes) AlpacaErrors.venue { trading.assets(assetClass) }.map(::listed) else emptyList()

    private fun listed(asset: JsonObject) =
        AlpacaListed(
            AlpacaInstruments.instrument(asset, fractional),
            asset.need("symbol"),
            asset.need("class") == AlpacaSettings.CRYPTO,
        )
}
