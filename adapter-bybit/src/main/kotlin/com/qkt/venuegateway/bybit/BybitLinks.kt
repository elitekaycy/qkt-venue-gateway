package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.bybit.client.BybitAccountStream
import com.qkt.venuegateway.bybit.client.BybitExecution
import com.qkt.venuegateway.bybit.client.BybitMarketStream
import com.qkt.venuegateway.bybit.client.BybitOrder
import com.qkt.venuegateway.bybit.client.BybitPrint
import com.qkt.venuegateway.bybit.client.BybitTicker

/** Opens the account socket: its order and execution pushes and its link going up or down. */
typealias AccountLink = (
    onOrder: (BybitOrder) -> Unit,
    onExecution: (BybitExecution) -> Unit,
    onConnection: (Boolean, String) -> Unit,
) -> BybitAccountStream

/** Opens the public socket: tickers, trades, liquidations, and its link going up or down. */
typealias MarketLink = (
    onTicker: (BybitTicker) -> Unit,
    onTrades: (String, List<BybitPrint>) -> Unit,
    onLiquidations: (String, List<BybitPrint>) -> Unit,
    onConnection: (Boolean, String) -> Unit,
) -> BybitMarketStream
