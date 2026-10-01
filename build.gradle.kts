// Shared conventions for every module; each module's build file declares only its dependencies.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
}

subprojects {
    group = "com.qkt.venuegateway"
    version = "0.1.0"
    repositories { mavenCentral() }
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(21) }
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> { version.set("1.5.0") }
    dependencies {
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.assertj.core)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }
    tasks.withType<Test> { useJUnitPlatform() }
}
