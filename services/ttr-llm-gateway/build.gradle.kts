import org.apache.tools.ant.taskdefs.condition.Os

// Prometheus — the LLM gateway, forked from ai-platform infra/llm-gateway.
// The repo's ONLY Spring Boot module (documented exception, AGENTS.md): every
// other kantheon JVM module is Ktor. Forked as-is — no Ktor rewrite.
//
// Test policy (planning-conventions.md §4 + Bora 2026-06-14): mocked unit tests
// only here; the integration suite (Testcontainers / WireMock / SpringBootTest)
// is designed and run separately, so those test deps are intentionally absent.

plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.dep)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.jib)
    alias(libs.plugins.ktlint)
}

group = "org.tatrman.llmgateway"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
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

// Image target is parameterized so the same jib config serves local dev and registry publish
// (mirrors agents/golem):
//   local : ./gradlew :services:ttr-llm-gateway:jibDockerBuild                 -> prometheus:dev
//   GHCR  : ./gradlew :services:ttr-llm-gateway:jib \
//             -PimageRepo=ghcr.io/boraperusic/prometheus -PimageTag=testing \
//             -Djib.to.auth.username=<gh-user> -Djib.to.auth.password=<ghcr-PAT>
val imageRepo = (project.findProperty("imageRepo") as String?) ?: "prometheus"
val imageTag = (project.findProperty("imageTag") as String?) ?: "dev"

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
        image = "$imageRepo:$imageTag"
    }
    container {
        mainClass = "org.tatrman.llmgateway.PrometheusApplicationKt"
        ports = listOf("7280")
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

dependencyManagement {
    imports {
        mavenBom(
            libs.opentelemetry.instrumentation.bom
                .get()
                .toString(),
        )
    }
}

dependencies {
    implementation(libs.boot.starter.web)
    implementation(libs.boot.starter.actuator)
    implementation(libs.boot.starter.data.jdbc)
    implementation(libs.boot.starter.oauth2.resource.server)
    implementation(libs.boot.starter.security)
    implementation(libs.springdoc.openapi.api)
    implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")

    // AI model starters (Spring AI)
    implementation(libs.ai.starter.model.azure.openai)
    implementation(libs.ai.starter.model.anthropic)

    // Kotlin
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)

    // DB
    implementation(libs.boot.starter.flyway)
    implementation(libs.flyway.core)
    implementation(libs.flyway.pgsql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)

    // Messaging
    implementation(libs.nats)

    // Response cache (Redis)
    implementation(libs.boot.starter.data.redis)

    // Proto
    implementation(project(":shared:proto"))
    implementation(libs.logstash.logback.encoder)

    // gRPC
    implementation(libs.boot.starter.grpc)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.java)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.spring.grpc.server.web.spring.boot.starter)

    // Config
    implementation(libs.typesafe.config)

    // Testing — mocked unit tests only (integration suite is separate)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
}
