package com.qkt.venuegateway.deribit.client

import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The private client against a scripted Deribit answering with the testnet recordings. */
class DeribitPrivateClientTest {
    private val server = MockWebServer().apply { start() }
    private val sent = LinkedBlockingQueue<JsonObject>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private var confirm: (List<String>) -> List<String> = { it }

    @AfterEach
    fun stop() = server.shutdown()

    private fun recorded(name: String) =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/private/$name")!!.readText()).jsonObject

    private fun answer(request: JsonObject): String {
        val id = request["id"]!!.jsonPrimitive.content
        val result =
            when (request["method"]!!.jsonPrimitive.content) {
                "public/auth" -> """{"access_token":"t","refresh_token":"r","expires_in":31536000}"""
                "private/subscribe" -> {
                    val asked = request["params"]!!.jsonObject["channels"]!!.jsonArray.map { it.jsonPrimitive.content }
                    Json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                        confirm(asked),
                    )
                }
                "private/buy" -> recorded("buy-limit-open.json")["response"]!!.jsonObject["result"].toString()
                "private/cancel" ->
                    recorded(
                        "cancel-market-remainder.json",
                    )["response"]!!.jsonObject["result"].toString()
                "private/get_account_summary" ->
                    recorded(
                        "account-summary.json",
                    )["response"]!!.jsonObject["result"].toString()
                else -> "\"ok\""
            }
        return """{"jsonrpc":"2.0","id":$id,"result":$result}"""
    }

    private fun venue() =
        MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    sockets += webSocket
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    val request = Json.parseToJsonElement(text).jsonObject
                    sent += request
                    webSocket.send(answer(request))
                }
            },
        )

    private fun client(
        orders: MutableList<DeribitOrder> = CopyOnWriteArrayList(),
        trades: MutableList<DeribitTrade> = CopyOnWriteArrayList(),
        connections: MutableList<Boolean> = CopyOnWriteArrayList(),
    ) = DeribitPrivateClient(
        server.url("/ws").toString().replace("http", "ws"),
        "id-7",
        "secret-7",
        setOf("USDC"),
        { orders += it },
        { trades += it },
        { up, _ -> connections += up },
    )

    @Test
    fun `it authenticates with the api key, subscribes every user channel, then places with exact numbers`() {
        server.enqueue(venue())
        val connections = CopyOnWriteArrayList<Boolean>()
        val client = client(connections = connections).also { it.start() }
        awaitTrue { connections.contains(true) }
        val methods = generateSequence { sent.poll() }.toList()
        val auth = methods.single { it["method"]!!.jsonPrimitive.content == "public/auth" }["params"]!!.jsonObject
        val subscribed = methods.single { it["method"]!!.jsonPrimitive.content == "private/subscribe" }

        assertThat(auth["grant_type"]!!.jsonPrimitive.content).isEqualTo("client_credentials")
        assertThat(auth["client_id"]!!.jsonPrimitive.content).isEqualTo("id-7")
        assertThat(subscribed["params"]!!.jsonObject["channels"]!!.jsonArray.map { it.jsonPrimitive.content })
            .containsExactlyInAnyOrder(
                "user.orders.future.USDC.raw",
                "user.trades.future.USDC.raw",
                "user.orders.option.USDC.raw",
                "user.trades.option.USDC.raw",
            )

        val order =
            client.place(
                DeribitNewOrder(
                    "kit-1",
                    "BTC_USDC-PERPETUAL",
                    "buy",
                    "limit",
                    BigDecimal("0.0001"),
                    BigDecimal("50461.9"),
                    null,
                    null,
                    "good_til_cancelled",
                    false,
                ),
            )
        val buy = sent.poll(5, TimeUnit.SECONDS)!!
        assertThat(buy["method"]!!.jsonPrimitive.content).isEqualTo("private/buy")
        assertThat(buy["params"]!!.jsonObject["amount"].toString()).isEqualTo("0.0001")
        assertThat(buy["params"]!!.jsonObject["price"].toString()).isEqualTo("50461.9")
        assertThat(buy["params"]!!.jsonObject).doesNotContainKey("trigger_price")
        assertThat(order.state).isEqualTo("open")
        client.close()
    }

    @Test
    fun `order and trade pushes reach their handlers, trades arriving as a list`() {
        server.enqueue(venue())
        val orders = CopyOnWriteArrayList<DeribitOrder>()
        val trades = CopyOnWriteArrayList<DeribitTrade>()
        val connections = CopyOnWriteArrayList<Boolean>()
        val client = client(orders, trades, connections).also { it.start() }
        awaitTrue { connections.contains(true) }

        recorded(
            "ws-user-notifications.json",
        )["notifications"]!!.jsonArray.forEach { sockets.single().send(it.toString()) }

        awaitTrue { orders.size == 4 && trades.size == 2 }
        assertThat(orders.map { it.state }).containsExactly("open", "cancelled", "filled", "filled")
        assertThat(trades.map { it.tradeId }).doesNotHaveDuplicates()
        client.close()
    }

    @Test
    fun `a connect whose subscriptions Deribit does not all confirm is never reported up`() {
        server.enqueue(venue())
        confirm = { asked -> asked.filterNot { it.startsWith("user.trades.option") } }
        val connections = CopyOnWriteArrayList<Boolean>()
        val client = client(connections = connections).also { it.start() }

        Thread.sleep(1_500)

        assertThat(connections).doesNotContain(true)
        client.close()
    }

    @Test
    fun `the account summary is asked for exactly as it was recorded, so the fixture is the real answer`() {
        server.enqueue(venue())
        val connections = CopyOnWriteArrayList<Boolean>()
        val client = client(connections = connections).also { it.start() }
        awaitTrue { connections.contains(true) }
        generateSequence { sent.poll() }.toList()

        val account = client.account("USDC")

        val asked = sent.poll(5, TimeUnit.SECONDS)!!
        assertThat(asked["params"]).isEqualTo(recorded("account-summary.json")["params"])
        assertThat(account.currency).isEqualTo("USDC")
        client.close()
    }

    @Test
    fun `a remainder is cancelled by its order id as recorded, waited for or not`() {
        server.enqueue(venue())
        val connections = CopyOnWriteArrayList<Boolean>()
        val client = client(connections = connections).also { it.start() }
        awaitTrue { connections.contains(true) }
        generateSequence { sent.poll() }.toList()
        val params = recorded("cancel-market-remainder.json")["params"]!!.jsonObject
        val id = params["order_id"]!!.jsonPrimitive.content

        assertThat(client.cancel(id).state).isEqualTo("cancelled")
        assertThat(sent.poll(5, TimeUnit.SECONDS)!!["params"]).isEqualTo(params)
        client.cancelSoon(id)
        assertThat(sent.poll(5, TimeUnit.SECONDS)!!["method"]!!.jsonPrimitive.content).isEqualTo("private/cancel")
        client.close()
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }
}
