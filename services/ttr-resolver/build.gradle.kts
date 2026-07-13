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
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.ktor.client.mock)
}
