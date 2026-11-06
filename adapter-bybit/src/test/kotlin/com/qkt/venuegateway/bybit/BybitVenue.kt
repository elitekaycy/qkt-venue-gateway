package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.bybit.client.BybitAccountStream
import com.qkt.venuegateway.bybit.client.BybitExecution
import com.qkt.venuegateway.bybit.client.BybitMarketStream
import com.qkt.venuegateway.bybit.client.BybitOrder
import com.qkt.venuegateway.bybit.client.BybitPrint
import com.qkt.venuegateway.bybit.client.BybitPrivateClient
import com.qkt.venuegateway.bybit.client.BybitPrivateJson
import com.qkt.venuegateway.bybit.client.BybitPublicClient
import com.qkt.venuegateway.bybit.client.BybitRest
import com.qkt.venuegateway.bybit.client.BybitTicker
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.RecordedRequest

/**
 * A [BybitAdapter] over a [Fixtures.Server] answering by [route], its sockets' callbacks kept so a test can push
 * what Bybit pushed. The account socket points nowhere (it never comes up); the market socket at [marketUrl].
 */
internal class BybitVenue(
    dir: Path,
    route: (path: String, query: Map<String, String>) -> String? = ::linearRoute,
    settings: Map<String, String> = LINEAR,
    val now: Long = NOW,
    marketUrl: String = NOWHERE,
) : AutoCloseable {
    val server = Fixtures.Server(route)
    lateinit var onOrder: (BybitOrder) -> Unit
    lateinit var onExecution: (BybitExecution) -> Unit
    lateinit var onAccountLink: (Boolean, String) -> Unit
    lateinit var onTicker: (BybitTicker) -> Unit
    lateinit var onTrades: (String, List<BybitPrint>) -> Unit
    lateinit var onLiquidations: (String, List<BybitPrint>) -> Unit
    lateinit var onMarketLink: (Boolean, String) -> Unit
    val context = AdapterContext(settings, { now }, dir, Credentials("the-key", "the-secret"))
    private val bybit = BybitSettings.of(context)
    val adapter =
        BybitAdapter(
            context,
            BybitPublicClient(BybitRest(server.url)),
            BybitPrivateClient(
                BybitRest(server.url, "the-key", "the-secret", { now }),
                bybit.category,
                bybit.currency.takeIf { bybit.contracts },
            ),
            { order, execution, link ->
                onOrder = order
                onExecution = execution
                onAccountLink = link
                BybitAccountStream(
                    NOWHERE,
                    "the-key",
                    "the-secret",
                    { now },
                    bybit.category,
                    order,
                    execution,
                    onConnection = link,
                )
            },
            { ticker, trades, liquidations, link ->
                onTicker = ticker
                onTrades = trades
                onLiquidations = liquidations
                onMarketLink = link
                BybitMarketStream(marketUrl, bybit.contracts, ticker, trades, liquidations, onConnection = link)
            },
        )

    /** The requests sent to [path], in order. */
    fun sent(path: String): List<RecordedRequest> =
        synchronized(server.requests) { server.requests.toList() }.filter { it.url.encodedPath == path }

    override fun close() {
        adapter.close()
        server.close()
    }

    companion object {
        /** Inside the recorded orders and executions. */
        const val NOW = 1_791_270_740_000L
        const val NOWHERE = "ws://127.0.0.1:1/v5"
        val LINEAR = mapOf("environment" to "testnet", "category" to "linear")
        val SPOT = mapOf("environment" to "testnet", "category" to "spot")

        /** The items of a recorded answer's `result.list`, or of a recorded push's `data`. */
        fun items(name: String): List<JsonObject> {
            val root = Json.parseToJsonElement(Fixtures.text(name)).jsonObject
            val list = root["result"]?.jsonObject?.get("list") ?: root["data"]!!
            return list.jsonArray.map { it.jsonObject }
        }

        fun order(name: String): BybitOrder = BybitPrivateJson.order(items(name).first())

        fun execution(name: String): BybitExecution = BybitPrivateJson.execution(items(name).first())

        /** A linear USDT account holding a BTCUSDT long in one-way mode, its resting limit `rec-rest-1` working. */
        fun linearRoute(
            path: String,
            query: Map<String, String>,
        ): String? =
            when {
                "cursor" in query -> "private/executions-empty.json"
                path == "/v5/market/instruments-info" -> "instruments-linear-BTCUSDT.json"
                path == "/v5/position/list" && "symbol" in query -> "private/position-slots-one-way.json"
                path == "/v5/position/list" -> "private/position-long.json"
                path == "/v5/account/wallet-balance" -> "private/wallet.json"
                path == "/v5/order/realtime" && query["orderLinkId"] == "rec-rest-1" -> "private/order-limit-new.json"
                path == "/v5/order/realtime" && "orderLinkId" in query -> "private/order-realtime-unknown.json"
                path == "/v5/order/realtime" -> "private/open-orders.json"
                path == "/v5/order/history" -> "private/order-history-unknown.json"
                path == "/v5/order/create" -> "private/create-limit.json"
                path == "/v5/order/cancel" -> "private/cancel-limit.json"
                path == "/v5/order/amend" -> "private/amend-limit.json"
                path == "/v5/execution/list" && query["execType"] == "Funding" -> "private/executions-funding.json"
                path == "/v5/execution/list" -> "private/executions-stop-fired.json"
                else -> marketRoute(path)
            }

        /** Bybit's recorded public answers for BTCUSDT. */
        fun marketRoute(path: String): String? =
            when (path) {
                "/v5/market/funding/history" -> "funding-history.json"
                "/v5/market/open-interest" -> "open-interest-5min.json"
                "/v5/market/tickers" -> "ticker-linear-BTCUSDT.json"
                "/v5/market/recent-trade" -> "recent-trade-linear.json"
                "/v5/market/orderbook" -> "orderbook-linear.json"
                "/v5/market/kline" -> "kline-linear-1m-15.json"
                "/v5/market/mark-price-kline" -> "mark-kline-1m-15.json"
                "/v5/market/index-price-kline" -> "index-kline-1m-15.json"
                else -> null
            }
    }
}
