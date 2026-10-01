plugins { alias(libs.plugins.kotlin.serialization) }

dependencies {
    "implementation"(project(":vgp-wire"))
    "implementation"(project(":adapter-api"))
    "implementation"(libs.ktor.server.core)
    "implementation"(libs.ktor.server.netty)
    "implementation"(libs.ktor.server.websockets)
    "implementation"(libs.kotlinx.serialization.json)
    "implementation"(libs.sqlite.jdbc)
    "implementation"(libs.slf4j.api)
    "testImplementation"(libs.ktor.server.test.host)
    "testImplementation"(libs.okhttp)
}
