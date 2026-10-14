package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueAdapterFactory
import com.qkt.venuegateway.bybit.client.BybitAccountStream
import com.qkt.venuegateway.bybit.client.BybitMarketStream
import com.qkt.venuegateway.bybit.client.BybitPrivateClient
import com.qkt.venuegateway.bybit.client.BybitPublicClient
import com.qkt.venuegateway.bybit.client.BybitRest

/** `GATEWAY_ADAPTER=bybit`: one category of one Bybit unified account, on the environment its settings name ([BybitSettings]). */
class BybitAdapterFactory : VenueAdapterFactory {
    override val type = "bybit"

    override fun create(context: AdapterContext): VenueAdapter {
        val settings = BybitSettings.of(context.settings)
        val credentials = context.requiredCredentials()
        val environment = settings.environment
        val signed = BybitRest(environment.restUrl, credentials.login, credentials.secret, context.clock)
        val settleCoin = settings.currency.takeIf { settings.contracts }
        return BybitAdapter(
            context,
            BybitPublicClient(BybitRest(environment.restUrl)),
            BybitPrivateClient(signed, settings.category, settleCoin),
            { onOrder, onExecution, onConnection ->
                BybitAccountStream(
                    "${environment.socketUrl}/v5/private",
                    credentials.login,
                    credentials.secret,
                    context.clock,
                    settings.category,
                    onOrder,
                    onExecution,
                    onConnection,
                )
            },
            { onTicker, onTrades, onLiquidations, onConnection ->
                BybitMarketStream(
                    "${environment.socketUrl}/v5/public/${settings.category}",
                    settings.contracts,
                    onTicker,
                    onTrades,
                    onLiquidations,
                    onConnection,
                )
            },
        )
    }
}
