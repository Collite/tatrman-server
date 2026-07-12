// SPDX-License-Identifier: Apache-2.0
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

dependencies {
    api(project(":shared:proto"))
    implementation(libs.protobuf.java.util)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.opentelemetry.api)
    implementation(libs.kotlin.logging)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.wiremock)
    testImplementation(libs.mockk)
}

// Publishing is handled by the root build's publishing convention (S5): publishes
// org.tatrman:capabilities-client:0.0.1-LOCAL to Maven Local for kantheon to consume.

tasks.named<Test>("test") {
    useJUnitPlatform()
}
