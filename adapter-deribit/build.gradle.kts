// One venue, one module: the Deribit protocol (`client/`), its mapping to adapter-api, and the adapter.
plugins { alias(libs.plugins.kotlin.serialization) }

dependencies {
    "api"(project(":adapter-api"))
    "implementation"(project(":venue-support"))
    "implementation"(libs.slf4j.api)
    "testImplementation"(project(":adapter-testkit"))
    "testImplementation"(libs.okhttp.mockwebserver)
}
