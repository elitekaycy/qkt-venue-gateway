package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueAdapterFactory
import com.qkt.venuegateway.deribit.client.DeribitPrivateClient
import com.qkt.venuegateway.deribit.client.DeribitPublicClient
import com.qkt.venuegateway.deribit.client.DeribitTickerStream

/** `GATEWAY_ADAPTER=deribit`: one Deribit account on the environment its settings name ([DeribitSettings]). */
class DeribitAdapterFactory : VenueAdapterFactory {
    override val type = "deribit"

    override fun create(context: AdapterContext): VenueAdapter {
        val settings = DeribitSettings.of(context.settings)
        val credentials = context.requiredCredentials()
        val environment = settings.environment
        return DeribitAdapter(
            context,
            DeribitPublicClient(environment.httpUrl),
            { onOrder, onTrade, onConnection ->
                DeribitPrivateClient(
                    environment.socketUrl,
                    credentials.login,
                    credentials.secret,
                    setOf(settings.currency),
                    onOrder,
                    onTrade,
                    onConnection,
                )
            },
            { onTicker, onConnection -> DeribitTickerStream(environment.socketUrl, onTicker, onConnection) },
            history = DeribitPublicClient(environment.historyUrl),
        )
    }
}
