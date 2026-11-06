// Shared conventions for every module; each module's build file declares only its dependencies.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kover)
}

subprojects {
    group = "com.qkt.venuegateway"
    version = rootProject.file("VERSION").readText().trim()
    repositories { mavenCentral() }
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "org.jetbrains.kotlinx.kover")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(21) }
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> { version.set("1.5.0") }
    dependencies {
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.assertj.core)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }
    tasks.withType<Test> { useJUnitPlatform() }
    // Adapters report the release they ship in (`adapter_version`) from their jar's manifest.
    tasks.withType<Jar> { manifest { attributes("Implementation-Version" to project.version) } }
}

// Coverage (docs/project/test-coverage.md): one merged Kover report over every module. `koverVerify`
// fails the build when total line or branch coverage drops below config/coverage-floor.properties;
// the floor only rises.
dependencies { subprojects.forEach { kover(it) } }

val coverageFloor =
    java.util.Properties().apply { file("config/coverage-floor.properties").inputStream().use { load(it) } }

kover {
    reports {
        filters { excludes { annotatedBy("*.ExcludeFromCoverage") } }
        verify {
            rule("line coverage floor") {
                bound {
                    coverageUnits.set(kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
                    minValue.set(coverageFloor.getProperty("line").toInt())
                }
            }
            rule("branch coverage floor") {
                bound {
                    coverageUnits.set(kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
                    minValue.set(coverageFloor.getProperty("branch").toInt())
                }
            }
        }
    }
}

tasks.register("check") {
    group = "verification"
    description = "Every module's checks plus the merged coverage floor."
    dependsOn(subprojects.map { "${it.path}:check" }, "koverVerify")
}
tasks.register("build") {
    group = "build"
    description = "Every module's build plus the merged coverage floor."
    dependsOn(subprojects.map { "${it.path}:build" }, "koverVerify")
}
