// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

// Shared support for the **component** test tier (testing arc, Phase 1 Stage 1.2):
// pinned Testcontainers factories, a WireMock admin-API helper (the runtime
// fixture-load protocol the integration tier reuses), and the CI-gating
// condition for the amd64-only MSSQL image. Consumers wire it into their own
// `componentTest` source set:  `"componentTestImplementation"(project(...))`.
//
// Testcontainers + the Kotest framework API are `api` so they land on the
// consumer's componentTest compile classpath transitively.
dependencies {
    api(libs.testcontainers)
    api(libs.testcontainers.mssqlserver)
    api(libs.testcontainers.postgresql)
    // Kotest runner as `api` — carries the framework API (Condition/@Tags/Spec)
    // the testkit compiles against, and the engine consumers run specs with.
    api(libs.kotest.runner.junit5)

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
}
