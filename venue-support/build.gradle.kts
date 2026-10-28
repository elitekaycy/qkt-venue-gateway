// What every venue adapter needs and no venue decides: a reconnecting socket, JSON over HTTP, exact JSON readers,
// paging, retry and signing. Adapters depend on it; it depends on no other module of the gateway.
plugins { alias(libs.plugins.kotlin.serialization) }

dependencies {
    "api"(libs.okhttp)
    "api"(libs.kotlinx.serialization.json)
    "implementation"(libs.slf4j.api)
    "testImplementation"(libs.okhttp.mockwebserver)
}
