plugins { alias(libs.plugins.kotlin.serialization) }

dependencies {
    "implementation"(project(":adapter-api"))
    "implementation"(project(":deribit-client"))
    "implementation"(libs.kotlinx.serialization.json)
    "implementation"(libs.slf4j.api)
}
