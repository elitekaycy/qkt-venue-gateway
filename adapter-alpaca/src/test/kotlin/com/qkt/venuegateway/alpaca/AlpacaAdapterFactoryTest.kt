package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.adapter.TradeMode
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The factory wires the adapter onto the host's client: REST answered from recorded fixtures and every socket's
 * opening refused offline, so the test sees where each call and socket goes without reaching Alpaca.
 */
class AlpacaAdapterFactoryTest {
    private val rest =
        mapOf(
            "/v2/account" to "account.json",
            "/v2/assets" to "assets-crypto.json",
            "/v2/assets/AAPL" to "asset-AAPL.json",
        )

    /** Every REST call the client made, and every socket it tried to open, as `host path`. */
    private val calls = LinkedBlockingQueue<String>()
    private val sockets = LinkedBlockingQueue<String>()

    private val offline =
        Interceptor { chain ->
            val request = chain.request()
            val where = "${request.url.host} ${request.url.encodedPath}"
            if (request.header("Upgrade") == "websocket") {
                sockets += where
                throw IOException("offline")
            }
            calls += where
            val fixture = rest[request.url.encodedPath] ?: throw IOException("offline: $where")
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(Fixtures.text(fixture).toResponseBody("application/json".toMediaType()))
                .build()
        }

    private fun context(
        settings: Map<String, String>,
        credentials: Credentials? = Credentials("key-id", "secret-value"),
    ) = AdapterContext(settings, { 0L }, Files.createTempDirectory("alpaca"), credentials, client)

    private val client = OkHttpClient.Builder().addInterceptor(offline).build()

    private fun next(queue: LinkedBlockingQueue<String>) = queue.poll(10, TimeUnit.SECONDS)

    @Test
    fun `the factory is the alpaca type and refuses an account without its key or its environment`() {
        val factory = AlpacaAdapterFactory()
        assertThat(factory.type).isEqualTo("alpaca")
        assertThatThrownBy { factory.create(context(mapOf("environment" to "paper"), credentials = null)) }
            .hasMessageContaining("credentials are required")
        assertThatThrownBy { factory.create(context(emptyMap())) }.hasMessageContaining("environment")
    }

    @Test
    fun `a paper account reads its identity from the paper trading host through the host's client`() {
        val adapter = AlpacaAdapterFactory().create(context(mapOf("environment" to "paper")))
        val identity = adapter.identity()
        assertThat(identity.login).isEqualTo("PA0000000000")
        assertThat(identity.mode).isEqualTo(TradeMode.DEMO)
        assertThat(next(calls)).isEqualTo("paper-api.alpaca.markets /v2/account")
        adapter.close()
    }

    @Test
    fun `connecting opens the trade-update stream of the account's environment`() {
        val adapter = AlpacaAdapterFactory().create(context(mapOf("environment" to "live")))
        adapter.connect(HeardListener())
        assertThat(next(sockets)).isEqualTo("api.alpaca.markets /stream")
        adapter.close()
    }

    @Test
    fun `quotes open the crypto, stock and overnight feeds on the data stream, each at its first subscription`() {
        val adapter =
            AlpacaAdapterFactory().create(
                context(
                    mapOf(
                        "environment" to "paper",
                        "asset_classes" to "us_equity,crypto",
                        "extended_hours" to "true",
                    ),
                ),
            )
        adapter.subscribeQuotes(setOf("AAPL", "BTCUSD"))
        val opened = listOf(next(sockets), next(sockets), next(sockets))
        assertThat(opened).containsExactlyInAnyOrder(
            "stream.data.alpaca.markets /v1beta3/crypto/us",
            "stream.data.alpaca.markets /v2/iex",
            "stream.data.alpaca.markets /v1beta1/overnight",
        )
        assertThat(calls).contains("paper-api.alpaca.markets /v2/assets/AAPL", "paper-api.alpaca.markets /v2/assets")
        // A changed subscription goes on the open links, which drop it while they are down: no new socket opens.
        adapter.subscribeQuotes(setOf("ETHUSD"))
        assertThat(sockets.poll(200, TimeUnit.MILLISECONDS)).isNull()
        adapter.close()
    }
}
