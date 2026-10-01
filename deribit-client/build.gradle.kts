plugins { alias(libs.plugins.kotlin.serialization) }

dependencies {
    "implementation"(libs.okhttp)
    "implementation"(libs.kotlinx.serialization.json)
    "implementation"(libs.slf4j.api)
    "testImplementation"(libs.okhttp.mockwebserver)
}
