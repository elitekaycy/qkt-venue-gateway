plugins { application }

dependencies {
    "implementation"(project(":host"))
    "implementation"(project(":adapter-api"))
    "implementation"(project(":adapter-paper"))
    "implementation"(project(":adapter-deribit"))
    "implementation"(libs.slf4j.api)
    "runtimeOnly"(libs.logback.classic)
    "testImplementation"(libs.okhttp)
}

application {
    mainClass.set("com.qkt.venuegateway.MainKt")
    applicationName = "qkt-venue-gateway"
}

// `./gradlew :app:run` resolves a relative GATEWAY_STATE_DIR from the repository root.
tasks.named<JavaExec>("run") { workingDir = rootDir }
