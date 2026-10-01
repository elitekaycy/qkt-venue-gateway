package com.qkt.venued.paper

import com.qkt.venued.adapter.AdapterContext
import com.qkt.venued.adapter.VenueAdapter
import com.qkt.venued.adapter.VenueAdapterFactory
import com.qkt.venued.deribit.DeribitPublicClient
import com.qkt.venued.deribit.DeribitTickerStream

/**
 * `adapter.type: paper`: the paper venue on Deribit's public data. Optional settings beyond
 * [PaperAdapter]'s: `deribit_url` (default `https://www.deribit.com`) and `deribit_ws_url` (default
 * `wss://www.deribit.com/ws/api/v2`); the testnet's are `https://test.deribit.com` and
 * `wss://test.deribit.com/ws/api/v2`.
 */
class PaperAdapterFactory : VenueAdapterFactory {
    override val type = "paper"

    override fun create(context: AdapterContext): VenueAdapter {
        val market = DeribitPublicClient(context.settings["deribit_url"] ?: "https://www.deribit.com")
        val ws = context.settings["deribit_ws_url"] ?: "wss://www.deribit.com/ws/api/v2"
        return PaperAdapter(
            context,
            market,
        ) { onTicker, onConnection -> DeribitTickerStream(ws, onTicker, onConnection) }
    }
}
