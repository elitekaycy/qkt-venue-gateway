package com.qkt.venuegateway

/**
 * Marks a declaration the coverage report skips: code only a running process reaches, such as `main` waiting on
 * real signals. Every use states its [reason]; code a test could reach is tested instead (docs/project/test-coverage.md).
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR)
annotation class ExcludeFromCoverage(
    val reason: String,
)
