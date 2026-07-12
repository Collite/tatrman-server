// SPDX-License-Identifier: Apache-2.0
import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.query.mcp.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

// The fat jar bundles the full Calcite-backed translator (ttr-translator) + gRPC +
// ktor, so it exceeds the 65535-entry ZIP limit — enable Zip64 (as kantheon's
// cli-app does). The ktor plugin's shadowJar is a shadow ShadowJar task.
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>().configureEach {
    isZip64 = true
}

tasks.test {
    useJUnitPlatform()
    // Arrow IPC reading (via data-formatter) needs these on JDK 17+.
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
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
        image = "query-mcp:dev"
    }
    container {
        mainClass = "org.tatrman.query.mcp.ApplicationKt"
        ports = listOf("7307")
        // Arrow needs these on JDK 21.
        jvmFlags =
            listOf(
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            )
    }
    dockerClient {
        executable = "docker"
        val targetSocket =
            System.getenv("DOCKER_HOST")
                ?: if (Os.isFamily(Os.FAMILY_MAC)) {
                    "unix://${System.getProperty("user.home")}/.rd/docker.sock"
                } else {
                    "npipe:////./pipe/docker_engine"
                }
        environment = mapOf("DOCKER_HOST" to targetSocket)
    }
}

dependencies {
    implementation(project(":shared:proto"))
    implementation(project(":shared:libs:kotlin:data-formatter"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:logging-config"))
    implementation(project(":shared:libs:kotlin:ktor-configurator"))

    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.kotlin.mcp.sdk)
    implementation(libs.typesafe.config)
    implementation(libs.kotlinx.coroutines.core)

    // Stage 3.5 T5 — load run_query/compile ToolCapability manifests + register
    // with capabilities-mcp (warn-and-continue), mirroring the other MCP wrappers.
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)
    implementation(project(":shared:libs:kotlin:capabilities-client"))

    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    implementation(libs.otel.logback.appender)
    implementation(libs.ktor.opentelemetry)
    implementation(libs.micrometer.registry.prometheus)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    // Arrow IPC builders for tests that feed the formatter a real Arrow stream.
    testImplementation(libs.arrow.vector)
    testImplementation(libs.arrow.memory.netty)
    // Stage 3.5 T6 — the full-chain component test wires run_query through a real
    // in-process Query (QueryServiceImpl with mocked Translate/Validate/Dispatch/worker).
    testImplementation(project(":services:ttr-query"))
    // Stage 4.1 T3 — in-memory span exporter for the run_query trace-nesting test.
    testImplementation(libs.opentelemetry.sdk.testing)

    // Integration tier (testing arc Stage 2.2) — drives the `query` tool over real
    // HTTP/MCP (StreamableHTTP) against the live `query-runquery` context. The
    // convention's integrationTest suite already brings kotest + project(); these
    // add the harness (@RequiresContext/ContextHandle), the MCP SDK client, a Ktor
    // HTTP engine, and JSON parsing for the CallToolResult.
    "integrationTestImplementation"(project(":shared:libs:kotlin:integration-harness"))
    "integrationTestImplementation"(libs.kotlin.mcp.sdk)
    "integrationTestImplementation"(libs.ktor.client.cio)
    "integrationTestImplementation"(libs.kotlinx.coroutines.core)
    "integrationTestImplementation"(libs.kotlinx.serialization.json)
}
