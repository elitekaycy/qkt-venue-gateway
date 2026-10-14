// One venue, one module: the Bybit v5 protocol (`client/`), its mapping to adapter-api, and the adapter.
plugins { alias(libs.plugins.kotlin.serialization) }

dependencies {
    "api"(project(":adapter-api"))
    "implementation"(libs.okhttp)
    "implementation"(libs.kotlinx.serialization.json)
    "implementation"(libs.slf4j.api)
    "testImplementation"(project(":adapter-testkit"))
    "testImplementation"(libs.okhttp.mockwebserver)
}
