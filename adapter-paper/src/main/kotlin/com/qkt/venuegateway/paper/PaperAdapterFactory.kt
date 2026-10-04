package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueAdapterFactory
import com.qkt.venuegateway.deribit.client.DeribitPublicClient
import com.qkt.venuegateway.deribit.client.DeribitTickerStream

/**
 * `GATEWAY_ADAPTER=paper`: the paper venue on Deribit's public data. Optional settings beyond
 * [PaperAdapter]'s: `deribit_url` (default `https://www.deribit.com`), `deribit_ws_url` (default
 * `wss://www.deribit.com/ws/api/v2`) and `deribit_history_url` (default `https://history.deribit.com`, the
 * whole trade history marks are read from); the testnet's are `https://test.deribit.com` (all three; its
 * trades reach back about a day) and `wss://test.deribit.com/ws/api/v2`.
 */
class PaperAdapterFactory : VenueAdapterFactory {
    override val type = "paper"

    override fun create(context: AdapterContext): VenueAdapter {
        val market = DeribitPublicClient(context.settings["deribit_url"] ?: "https://www.deribit.com")
        val ws = context.settings["deribit_ws_url"] ?: "wss://www.deribit.com/ws/api/v2"
        val history = DeribitPublicClient(context.settings["deribit_history_url"] ?: "https://history.deribit.com")
        return PaperAdapter(context, market, history) { onTicker, onConnection ->
            DeribitTickerStream(ws, onTicker, onConnection)
        }
    }
}
