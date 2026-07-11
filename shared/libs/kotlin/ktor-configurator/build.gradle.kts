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
}
