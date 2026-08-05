// SPDX-License-Identifier: Apache-2.0
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    implementation(libs.typesafe.config)

    implementation(libs.kotlin.mcp.sdk)
    implementation(libs.ktor.opentelemetry)
    implementation(project(":shared:libs:kotlin:otel-config"))

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)

    // A real HTTP client, so the Netty engine tests can prove `responseWriteTimeoutSeconds`
    // actually reaches the engine (ST-P2·S1·T5). Asserting on the data class alone would pass
    // even if the `configure` block were deleted. The server side needs nothing extra —
    // `ktor-server-netty` is already an `implementation` dep above, which `testImplementation`
    // extends.
    testImplementation(libs.ktor.client.cio)

    // RV-P1.6 — the S-3 admin gate is a route-level decision, so it is tested through a real
    // routing tree rather than only through its pure predicate.
    testImplementation(libs.ktor.server.test.host)
}
