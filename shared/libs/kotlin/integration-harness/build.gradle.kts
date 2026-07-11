plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// Shared support for the **integration** test tier (testing arc, Phase 2 Stage
// 2.1): the `@RequiresContext` readiness gate, the `ContextHandle`, and a
// READ-ONLY fabric8 cluster reader. The kantheon side never applies/deletes —
// bring-up/teardown is olymp's (testing architecture §3). Consumers wire it into
// their `integrationTest` source set:  `"integrationTestImplementation"(project(...))`.
//
// fabric8 + the Kotest framework API are `api` so they land on the consumer's
// integrationTest compile classpath; component-testkit is reused for the
// in-cluster WireMock admin protocol (`WireMockAdmin`).
dependencies {
    api(libs.fabric8.kubernetes.client)
    api(libs.kotest.runner.junit5)
    api(project(":shared:libs:kotlin:component-testkit"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
}
