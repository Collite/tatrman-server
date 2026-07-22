// SPDX-License-Identifier: Apache-2.0
// Charon (Arrow data mover, strangler ④) — history-preserving transplant from kantheon (pin 860fd90),
// re-homed onto the platform build (PL-P3.S1.T2). Reworked off kantheon's shared:proto + published
// tatrman ktor/otel/logging helper libs onto :proto:hall-proto + inline micrometer (Veles precedent);
// jib dropped (T6 packages via installDist + a house Dockerfile like radegast). Behavior-unchanged:
// the transplanted Kotest suites stay green; the Arrow --add-opens and the bench entry point are kept.
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

application {
    mainClass.set("cz.tatrman.charon.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Arrow Java 18.x needs `--add-opens=java.base/java.nio=ALL-UNNAMED` for its Netty memory
    // allocator. Without it, the first `allocateNew` in a test crashes inside `MemoryUtil.<clinit>`
    // (arrow-memory-netty's allocator uses NIO internals the JVM 9+ module system blocks).
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
    systemProperty("file.encoding", "UTF-8")
}

// Charon move-core micro-bench. The bench lives on the test classpath
// (`src/test/.../bench/MovePipeBench.kt`); this gives it the single documented entry point
// `./gradlew :services:charon:bench`. Kept runnable, excluded from CI (PL-P3.S1.T2).
tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Run the Charon move-core micro-bench (rows/s + MB/s; network-free)."
    mainClass.set("cz.tatrman.charon.bench.MovePipeBenchKt")
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
}

dependencies {
    // The ④ hall proto: transfer.v1 (CharonService) + metis.v1 (carved) + cz.tatrman.common.v1,
    // and — transitively via hall-proto's `api` — the published worker.v1/plan.v1 classes.
    implementation(project(":proto:hall-proto"))
    // Secret-store SPI (contracts §17, H-5): per-transfer source×target connection resolution — the
    // TransferSecretInjector's SPI (never a dep on the hall service; same SPI, own injector).
    implementation(project(":shared:secrets-spi"))

    implementation(libs.kotlinx.coroutines.core)

    // Ktor — HTTP probes/status/metrics/refresh only (the real surface is the gRPC service).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    // Metrics — Prometheus scrape. Observability is inline micrometer (no otel transplant; Veles precedent).
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // gRPC — NettyServerBuilder direct (charon idiom). Stubs come via :proto:hall-proto's `api`.
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.services)

    // Arrow — the universal exchange format (IPC reader/writer ship inside arrow-vector in 18.x).
    implementation(libs.arrow.vector)
    implementation(libs.arrow.memory.netty)

    // Object-store + DB edges: Seaweed(S3), Redis, PG/MSSQL JDBC behind a Hikari pool per connection.
    implementation(libs.aws.sdk.s3)
    implementation(libs.lettuce.core)
    implementation(libs.postgresql)
    implementation(libs.mssql.jdbc)
    implementation(libs.hikaricp)
    // Connection-registry YAML. NOTE: the file-based registry is replaced by the secret-store SPI at
    // PL-P3.S1.T5 (per-transfer resolution); jackson-yaml stays until that lands.
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    // HOCON
    implementation(libs.typesafe.config)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    // In-process Ktor host for the HTTP probe/status route specs.
    testImplementation(libs.ktor.server.test.host)
    // In-process gRPC server for the component suite (the five RPCs over an in-process channel).
    testImplementation(libs.grpc.inprocess)
    // H2 in-memory DB — the unit-test stand-in JDBC driver for the DB-edge round-trips (real PG/MSSQL
    // dialect fidelity is the separate integration suite).
    testImplementation(libs.h2)
    // Testcontainers PG — the real query source for the T3 frozen contract tests (postgres:16-alpine,
    // the platform's veles/radegast image).
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
}
