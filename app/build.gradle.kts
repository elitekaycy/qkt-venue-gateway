plugins { application }

dependencies {
    "implementation"(project(":host"))
    "implementation"(project(":adapter-api"))
    "implementation"(project(":adapter-paper"))
    "implementation"(project(":adapter-deribit"))
    "implementation"(libs.snakeyaml.engine)
    "implementation"(libs.slf4j.api)
    "runtimeOnly"(libs.logback.classic)
    "testImplementation"(libs.okhttp)
}

application { mainClass.set("com.qkt.venuegateway.MainKt") }

// `./gradlew :app:run --args=config.yaml` resolves the config and its relative paths from the repository root.
tasks.named<JavaExec>("run") { workingDir = rootDir }
