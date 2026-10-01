// What an adapter's own tests extend: the contract every adapter keeps for the gateway.
dependencies {
    "api"(project(":adapter-api"))
    "api"(libs.junit.jupiter.api)
    "api"(libs.assertj.core)
}
