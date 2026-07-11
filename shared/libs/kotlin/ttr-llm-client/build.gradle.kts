// llm-gateway-client — the shared client for the LLM gateway LLM gateway
// (OpenAI-shaped /v1/chat/completions) + the Koog PromptExecutor bridge.
// Extracted from agents/themis (2026-06-19, Golem Stage 2.3 T1) so Golem's
// PlanComposer and Themis's nodes share one client instead of per-agent copies.
plugins {
    base
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
    // Koog — the PromptExecutor bridge implements ai.koog.prompt.executor.model.PromptExecutor.
    api(libs.koog.agents)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koog.agents.test)
    testImplementation(libs.mockk)
}
