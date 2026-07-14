// SPDX-License-Identifier: Apache-2.0
// RG-P5 — ttr-resolver: the deterministic resolver core (workstream E).
//
// The ONE rule of this module: ZERO LLM. There is deliberately NO dependency on
// `:shared:libs:kotlin:ttr-llm-client` (or any llm-gateway stub) — the LLM
// escalation ladder is the kantheon Resolving Agent's job (RS-23). NoLlmDependencyTest
// asserts this mechanically; keeping the dep out of this file is the first line.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.resolver.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

val osArch = System.getProperty("os.arch").lowercase()
val isArm64 = osArch.contains("aarch64") || osArch.contains("arm64")
val isCi = System.getenv("CI") != null

jib {
    from {
        image = "eclipse-temurin:21-jre"
        platforms {
            if (isCi) {
                platform {
                    architecture = "arm64"
                    os = "linux"
                }
                platform {
                    architecture = "amd64"
                    os = "linux"
                }
            } else {
                platform {
                    architecture = if (isArm64) "arm64" else "amd64"
                    os = "linux"
                }
            }
        }
    }
    to {
        image = "resolver:dev"
    }
    container {
        mainClass = "org.tatrman.resolver.ApplicationKt"
        ports = listOf("7275", "7276")
    }
    dockerClient {
        executable = "docker"
    }
}

dependencies {
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    api(libs.otel.logback.appender)
    implementation(project(":shared:libs:kotlin:logging-config"))

    // S-2 — the one normalization spec (`fold`). Span proposal folds declared
    // anchor words the same way ttr-fuzzy/the grounding kernel do; determinism
    // and cross-service parity require the byte-identical fold. Dependency-free leaf.
    implementation(project(":shared:libs:kotlin:ttr-text"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.typesafe.config)

    // OpenTelemetry
    implementation(libs.ktor.opentelemetry)
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(libs.micrometer.registry.prometheus)

    // gRPC — :shared:proto generates the ResolverService coroutine base + the
    // FuzzyService / NlpService client stubs the deterministic core calls.
    implementation(project(":shared:proto"))
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.services)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.grpc.inprocess)
}

// RG-P5 — structural ZERO-LLM guard (RS-23). Fail the build if the resolver's
// runtime classpath resolves ANY LLM client: the in-house llm-gateway client
// module (`:shared:libs:kotlin:ttr-llm-client`) or a known external LLM SDK.
// This enforces the invariant at the dependency-graph level; NoLlmDependencyTest
// is the runtime backstop. (The generated `org.tatrman.llm.v1` gRPC stub was split
// into its own `:shared:proto-llm` module, so it is no longer on this classpath —
// NoLlmDependencyTest asserts its absence as a hard forbidden class.)
val forbiddenLlmCoordinates =
    listOf("ttr-llm-client", "openai", "anthropic", "langchain4j", "langchain", "theokanning")

val verifyNoLlmDependency by tasks.registering {
    val runtimeClasspath = configurations.named("runtimeClasspath")
    doLast {
        val hits =
            runtimeClasspath
                .get()
                .resolvedConfiguration.resolvedArtifacts
                .map { it.moduleVersion.id }
                .filter { id ->
                    forbiddenLlmCoordinates.any { bad ->
                        id.name.contains(bad, ignoreCase = true) || id.group.contains(bad, ignoreCase = true)
                    }
                }.map { "${it.group}:${it.name}:${it.version}" }
                .distinct()
        require(hits.isEmpty()) {
            "ZERO-LLM violation (RS-23): resolver runtimeClasspath resolves LLM client artifact(s): $hits"
        }
    }
}

tasks.named("check") { dependsOn(verifyNoLlmDependency) }
