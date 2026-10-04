# Writing an adapter, step by step

A walk-through for a new venue, here called `acme`. The rules this follows, and what CI checks, are
in [adapters.md](adapters.md); the interface and why it looks this way are in [design.md](design.md) §3.
`adapter-deribit` is the complete worked example: open its files beside each step.

## 0. Can the venue be adapted?

Check these before writing code. If one fails, the venue cannot be adapted (yet):

- **An order label.** The venue stores a client-chosen id on each order and can look an order up by it
  (Deribit: `label`). The gateway puts qkt's `client_order_id` there; it is how a lost order is found.
- **Execution ids.** Each fill carries the venue's own trade id, so a fill seen twice counts once.
- **A test environment** with API keys, so the contract suite can place real orders.
- **Pushes** (WebSocket or similar) for orders and fills, or polling cheap enough to stand in for them.
- **Decimals as text,** or JSON numbers you can read as text: money never passes through `Double`.

## 1. Branch and module

```bash
git checkout dev && git pull
git checkout -b adapter/acme
mkdir -p adapter-acme/src/{main,test}/kotlin/com/qkt/venuegateway/acme/client \
         adapter-acme/src/main/resources/META-INF/services adapter-acme/src/test/resources/fixtures
```

`adapter-acme/build.gradle.kts`:

```kotlin
// One venue, one module: the Acme protocol (`client/`), its mapping to adapter-api, and the adapter.
plugins { alias(libs.plugins.kotlin.serialization) }

dependencies {
    "api"(project(":adapter-api"))
    "implementation"(libs.okhttp)
    "implementation"(libs.kotlinx.serialization.json)
    "implementation"(libs.slf4j.api)
    "testImplementation"(project(":adapter-testkit"))
    "testImplementation"(libs.okhttp.mockwebserver)
}
```

Add `"adapter-acme"` to `settings.gradle.kts`, and `"implementation"(project(":adapter-acme"))` to
`app/build.gradle.kts` so the gateway ships it.

## 2. Probe and record (checkpoint 1)

Call every endpoint the adapter will use on the venue's test environment, by hand (`curl`, `websocat`),
and save each answer as a fixture, account ids replaced:

```
adapter-acme/src/test/resources/fixtures/
  instruments.json  ticker.json  klines.json
  private/order-placed.json  private/order-by-label.json  private/trades.json  private/positions.json
```

Write what you measured into `adapter-acme/README.md` under "Venue facts": rate limits, id formats,
precision, how long a closed order stays queryable by label, which order types and time-in-force it
accepts, how errors look. These facts drive the code and the tests; guesses do not.

## 3. The client (checkpoint 2)

`client/` speaks Acme in Acme's words: `AcmeOrder`, `AcmeTrade`, `AcmeTicker`, plain data classes
whose decimals are `BigDecimal` read from the raw JSON text. It knows nothing of `adapter-api`.

```kotlin
/** Acme's REST API: listings, tickers, klines, and the private calls signed with the API key. */
class AcmeRestClient(private val baseUrl: String, private val key: String, private val secret: String) {
    fun placeOrder(request: AcmeOrderRequest): AcmeOrder = …
    fun orderByLabel(label: String): AcmeOrder? = …
    fun trades(fromMs: Long, toMs: Long): List<AcmeTrade> = …
}
```

Test it offline: `MockWebServer` serves the recorded fixtures, and the test checks both the request
the client sends and what it reads back.

## 4. The mapping (checkpoint 3)

The only file where Acme words become `adapter-api` types. Every field comes from a fixture; an
unknown status or kind fails loudly instead of guessing.

```kotlin
/** Acme's answers as adapter-api types. */
internal object AcmeMapping {
    fun order(o: AcmeOrder): VenueOrder = …
    fun fill(t: AcmeTrade): VenueFill = …          // venue trade id as the fill id
    fun status(s: String): OrderStatus =
        when (s) {
            "open" -> OrderStatus.WORKING
            "filled" -> OrderStatus.FILLED
            "cancelled" -> OrderStatus.CANCELLED
            "rejected" -> OrderStatus.REJECTED
            else -> error("acme order state $s is not known")
        }
}
```

`AcmeMappingTest` reads each fixture through the client and asserts every mapped field, decimals with
`isEqualByComparingTo`.

## 5. Settings, adapter and factory (checkpoint 4)

```kotlin
/** Acme's settings: `environment` (`testnet` or `mainnet`, never defaulted), `currency` (default `USDT`). */
data class AcmeSettings(val environment: AcmeEnvironment, val currency: String) {
    companion object {
        fun of(settings: Map<String, String>) =
            AcmeSettings(
                AcmeEnvironment.of(settings["environment"] ?: error("setting environment is required: testnet or mainnet")),
                settings["currency"] ?: "USDT",
            )
    }
}

/** `GATEWAY_ADAPTER=acme`: one Acme account on the environment its settings name. */
class AcmeAdapterFactory : VenueAdapterFactory {
    override val type = "acme"

    override fun create(context: AdapterContext): VenueAdapter {
        val settings = AcmeSettings.of(context.settings)
        val credentials = context.requiredCredentials()
        return AcmeAdapter(context, AcmeRestClient(settings.environment.url, credentials.login, credentials.secret))
    }
}
```

`AcmeAdapter : VenueAdapter` implements each call through the client and the mapping. The rules on
the interface's KDoc apply: `place` sends `clientOrderId` as the label; `connect` returns at once and
reports `connection(true)` when it can serve; a venue refusal is `VenueRefusedException(reason)`, the
venue being unreachable is `VenueUnavailableException`; pushes go to the listener and are never dropped.
Settings reach it as `GATEWAY_SETTING_<KEY>`; the login and secret as `GATEWAY_LOGIN`/`GATEWAY_SECRET`.

Register the factory, one line, in
`src/main/resources/META-INF/services/com.qkt.venuegateway.adapter.VenueAdapterFactory`:

```
com.qkt.venuegateway.acme.AcmeAdapterFactory
```

## 6. The contract suite (checkpoint 5)

```kotlin
/** The Acme adapter against Acme's testnet; runs only with ACME_CLIENT_ID and ACME_CLIENT_SECRET set. */
class AcmeContractTest : AdapterContractTest() {
    private val id = System.getenv("ACME_CLIENT_ID")
    private val secret = System.getenv("ACME_CLIENT_SECRET")

    @BeforeEach
    fun testnetKey() = assumeTrue(!id.isNullOrBlank() && !secret.isNullOrBlank(), "no testnet key")

    override fun newAdapter(stateDir: Path): VenueAdapter =
        AcmeAdapterFactory().create(
            AdapterContext(mapOf("environment" to "testnet"), System::currentTimeMillis, stateDir, Credentials(id, secret)),
        )

    override fun restingOrder(clientOrderId: String) = NewOrder(clientOrderId, "BTC-PERP", Side.BUY, OrderType.LIMIT, …)
    override fun fillingOrder(clientOrderId: String) = …
    override fun closingOrder(clientOrderId: String) = …   // reduceOnly = true
    override fun refusedOrder(clientOrderId: String) = …   // a size off the venue's step
    override val activeCode = "BTC-PERP"
    override val perpetualCode = "BTC-PERP"   // only when the adapter declares FUNDING_RATES
}
```

Declare `capabilities` honestly. The suite checks each declared one in shape (funding records unique,
in their window and never zero; funding rates ascending at positive prices; settlements in their window;
`activeCode`'s marks over its last 30 bar windows, at most one a window, positive) and requires every other
one of settlements, funding, funding rates and mark prices to throw `VenueUnsupportedException`. A venue that charges funding on perpetuals should declare `FUNDING`: without
it, qkt will not trade them.

Run it against testnet until it passes:

```bash
ACME_CLIENT_ID=… ACME_CLIENT_SECRET=… ./gradlew :adapter-acme:test --tests '*ContractTest'
```

## 7. Wire up and document (checkpoint 6)

- `adapter-acme/README.md`, shaped like the Deribit one: setup, connect qkt, settings (variable,
  default, meaning), venue behaviour and the contract suite.
- `adapter-acme/CLAUDE.md`: exactly `@README.md` and `@../docs/adapters.md`.
- Root `README.md`: a row in the Adapters table, linking to `adapter-acme/README.md`.
- `.github/workflows/adapter.yml`, in the test step's `env`:

  ```yaml
  ACME_CLIENT_ID: ${{ matrix.venue == 'acme' && secrets.ACME_CLIENT_ID || '' }}
  ACME_CLIENT_SECRET: ${{ matrix.venue == 'acme' && secrets.ACME_CLIENT_SECRET || '' }}
  ```

  and ask a maintainer to add the two repository secrets (a testnet key, trade scope only).

Then check locally what CI will:

```bash
./gradlew build && scripts/check-rules.sh && scripts/check-adapter.sh acme
```

## 8. End to end (checkpoint 7)

Run the gateway on testnet and trade through it from qkt:

```bash
docker build -t qkt-venue-gateway .
docker run --rm -p 127.0.0.1:8445:8443 -v gw-acme:/data \
  -e GATEWAY_TRADER_TOKEN=t -e GATEWAY_GUARDIAN_TOKEN=g \
  -e GATEWAY_ADAPTER=acme -e GATEWAY_SETTING_ENVIRONMENT=testnet \
  -e GATEWAY_LOGIN=… -e GATEWAY_SECRET=… qkt-venue-gateway
```

Point a qkt `type: gateway` broker entry at `http://127.0.0.1:8445` ([running.md](running.md#connect-qkt)),
place an order, see it fill on the stream, close the position. Note the run in the PR.

## 9. Open the pull request

Title `feat(acme): add the acme adapter, passing the contract suite on testnet`, from `adapter/acme`
into `dev`. The `pr` check holds the diff to the adapter's scope; the `adapter` check runs the layout
check, the tests and the contract suite on testnet, and fails if the suite was skipped.
