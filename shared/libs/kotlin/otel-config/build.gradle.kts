plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    // Expose as api so consumers get the OpenTelemetrySdk type directly
    api(libs.opentelemetry.sdk)
    api(libs.opentelemetry.exporter.otlp)
    // Coroutine-aware context propagation for the withSpan helper (Span.asContextElement).
    api(libs.opentelemetry.extension.kotlin)
    // Logback → OTEL bridge: routes SLF4J/Logback log records into SdkLoggerProvider
    api(libs.otel.logback.appender)
    // logback-classic is needed to compile against UnsynchronizedAppenderBase (supertype of OpenTelemetryAppender)
    compileOnly(libs.logback.classic)
    // withSpan is a suspend helper — needs coroutines core for withContext.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.opentelemetry.sdk.testing)
}
