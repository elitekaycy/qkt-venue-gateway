plugins { application }

dependencies {
    "implementation"(project(":host"))
    "implementation"(project(":adapter-api"))
    "implementation"(project(":adapter-paper"))
    "implementation"(project(":adapter-deribit"))
    "implementation"(libs.snakeyaml.engine)
    "implementation"(libs.slf4j.api)
    "runtimeOnly"(libs.logback.classic)
}

application { mainClass.set("com.qkt.venued.MainKt") }
