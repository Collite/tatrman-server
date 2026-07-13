// SPDX-License-Identifier: Apache-2.0
import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.geo.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
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
        image = "geo:dev"
    }
    container {
        mainClass = "org.tatrman.geo.ApplicationKt"
        ports = listOf("7121", "7221")
    }
    dockerClient {
        executable = "docker"
        val targetSocket =
            System.getenv("DOCKER_HOST") ?: if (Os.isFamily(Os.FAMILY_MAC)) {
                "unix://${System.getProperty("user.home")}/.rd/docker.sock"
            } else {
                "npipe:////./pipe/docker_engine"
            }
        environment = mapOf("DOCKER_HOST" to targetSocket)
    }
}

dependencies {
    implementation(libs.tatrman.ttr.translator)
    implementation(project(":shared:libs:kotlin:ttr-grounding-core"))
    implementation(project(":shared:libs:kotlin:ktor-configurator"))
    implementation(project(":shared:libs:kotlin:otel-config"))
    implementation(project(":shared:libs:kotlin:logging-config"))
    implementation(project(":shared:proto"))
    // JsonFormat — parse the llm-gateway fallback's GroundingResult JSON back into the proto.
    implementation(libs.protobuf.java.util)
    // JTS — GeoJSON polygon → WKT + bounding box for the boundary-containment recipe.
    implementation(libs.jts.core)

    // Durable boundary cache — shared DatabaseConnection (Hikari + Exposed), Exposed DSL for the
    // boundary_cache / place_alias tables, Flyway for the schema.
    implementation(project(":shared:libs:kotlin:db-common"))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.pgsql)

    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.services)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.typesafe.config)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.ktor.opentelemetry)
    api(libs.otel.logback.appender)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.wiremock)
    // InMemoryMetricReader for the GeoMetrics assertions.
    testImplementation(libs.opentelemetry.sdk.testing)
    // RG-P3.S0 metadata-seam component test — stands up an in-process Veles
    // (MetadataServiceImpl) over the semantics fixture to prove MetaV1GeoDiscovery
    // maps the real meta.v1 projection to the discovery domain types.
    testImplementation(project(":services:veles"))
    testImplementation(libs.tatrman.ttr.metadata)
    testImplementation(libs.grpc.inprocess)

    // Component tier — the durable Postgres boundary cache round-trips over a REAL
    // Postgres (Testcontainers). Physically separated into `src/componentTest` (repo
    // convention, mirror :services:veles) so the mocked-only unit `test` gate stays
    // Docker-free and green offline. The convention suite (root build) already carries
    // project()/kotest/testcontainers; these are geo-main `implementation` deps the
    // spec imports directly (not on the componentTest compile classpath transitively).
    "componentTestImplementation"(libs.testcontainers.postgresql)
    "componentTestImplementation"(project(":shared:libs:kotlin:db-common"))
    "componentTestImplementation"(libs.exposed.core)
    "componentTestImplementation"(libs.exposed.jdbc)
    "componentTestImplementation"(libs.postgresql)
    "componentTestImplementation"(libs.flyway.core)
    "componentTestImplementation"(libs.flyway.pgsql)
    "componentTestImplementation"(libs.typesafe.config)
}
