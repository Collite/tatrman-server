plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.kantheon.charon.ApplicationKt")
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "charon:dev"
    }
    container {
        mainClass = "org.tatrman.kantheon.charon.ApplicationKt"
        // P1 Stage 1.1: HTTP probes (7250) + gRPC service (7251).
        // charon-mcp (7252) is added when tools/charon-mcp lands in P3.
        ports = listOf("7250", "7251")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Arrow Java 18.x needs `--add-opens=java.base/java.nio=ALL-UNNAMED` for
    // its Netty memory allocator. Without it, the first `allocateNew` in
    // a test crashes inside `MemoryUtil.<clinit>`. AGENTS.md §10 — the
    // `arrow-memory-netty` artifact ships a Netty-backed allocator that
    // uses NIO internals; the JVM 9+ module system blocks reflection.
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
    // For Testcontainers — the Docker socket + /tmp mounts need broad
    // filesystem access. Same JVM opens as Postgres-Meta uses.
    systemProperty("file.encoding", "UTF-8")
}

// Charon move-core micro-bench (Stage 3.2 T4). The bench lives on the test
// classpath (`src/test/.../bench/MovePipeBench.kt`); this gives it the single
// documented entry point `./gradlew :services:charon:bench` instead of an
// IDE-only / hand-rolled `java -cp` invocation. See services/charon/bench/README.md.
tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Run the Charon move-core micro-bench (rows/s + MB/s; network-free)."
    mainClass.set("org.tatrman.kantheon.charon.bench.MovePipeBenchKt")
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
}

dependencies {
    // Shared libs (in-repo; AGENTS.md §5 — every shared dep is a project ref).
    implementation(project(":shared:proto"))
    implementation(libs.tatrman.ktor.configurator)
    implementation(libs.tatrman.otel.config)
    implementation(libs.tatrman.logging.config)

    // Kotlin / coroutines / serialization
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)

    // Ktor (HTTP probes only at Stage 1.1; MCP server framework deferred to P3).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.opentelemetry)
    // Metrics — Prometheus scrape (Stage 1.2 T5). The `charon_moves_total` counter
    // and `charon_move_duration_ms` timer live in the MoveExecutor; the
    // `Micrometer.PrometheusMeterRegistry` is the one shared instance.
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.otel.logback.appender)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // gRPC — kantheon idiom: NettyServerBuilder direct (charon/architecture.md §2 +
    // veles Application.kt precedent). gRPC-Java + gRPC-Kotlin are both
    // available via the proto module's `grpc-stub` / `grpc-kotlin-stub` transitives.
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.services)

    // Arrow — charon/architecture.md §2 (the universal exchange format).
    // The IPC reader/writer classes (ArrowStreamReader/Writer,
    // ArrowFileReader/Writer) ship inside `arrow-vector` in 18.x (no separate
    // artifact), so this is enough to start the move pipe. P2 Stage 2.1
    // adds `arrow-jdbc` + the ADBC drivers when the spike gate runs; they're
    // not consumed at the P1 skeleton.
    implementation(libs.arrow.vector)
    implementation(libs.arrow.memory.netty)

    // S3 client (P1 Stage 1.2 — SeaweedEndpoint).
    implementation(libs.aws.sdk.s3)

    // Redis (P1 Stage 1.3 — RedisEndpoint).
    implementation(libs.lettuce.core)

    // Database edges (P2 Stage 2.1/2.2 — ConnectionRegistry + AdbcReader/Writer).
    // Spike verdict (Stage 2.1 T3, see README §"ADBC spike verdict"): plain
    // JDBC behind the `AdbcReader`/`AdbcWriter` interface, hand-rolled
    // JDBC↔Arrow over the explicit contracts §5 type matrix — NOT the ADBC
    // driver-manager (immature on the JVM for MSSQL) and NOT arrow-jdbc's
    // auto-mapping (needs per-column overrides to hit the §5 matrix anyway).
    // PG + MSSQL JDBC drivers + a Hikari pool per connection.
    implementation(libs.postgresql)
    implementation(libs.mssql.jdbc)
    implementation(libs.hikaricp)
    // Connection-registry YAML (mirror capabilities-mcp's ManifestYamlLoader).
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    // HOCON
    implementation(libs.typesafe.config)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    // In-process Ktor host for the HTTP probe/status route specs (guards the
    // ContentNegotiation-or-406 CrashLoop trap — EXAMPLES.md §2a).
    testImplementation(libs.ktor.server.test.host)
    // In-process gRPC server for the component suite (Stage 1.1 T6: a
    // `RequestValidationSpec` exercises the five RPCs over an in-process channel
    // without needing a live K3s).
    testImplementation(libs.grpc.inprocess)
    // H2 in-memory DB — the unit-test stand-in JDBC driver for the DB-edge
    // reader/writer round-trips (P2). H2 runs in the test JVM (no external
    // infra), exercising the JDBC↔Arrow control flow + write-mode semantics;
    // real PG/MSSQL dialect fidelity is the separate integration suite
    // (charon/plan.md §4 — "Real-driver dialect fidelity … deferred").
    testImplementation(libs.h2)
    // **Stage 1.2 S3 testing is mocked, not Testcontainers** (Bora's call
    // 2026-06-14): the live Seaweed round-trip + fault injection land in a
    // separate integration-test pass against the real local K3s SeaweedFS
    // (charon/plan.md §3.2 T3 + T6 — "K3s integration. Live stack smoke
    // tests at the end of each stage that ships infra"). At Stage 1.2
    // the CharonMoveExecutor suite is `mockk`-driven against the S3Client
    // interface, asserting the move-pipe contract without a Docker
    // dependency. The other services' Testcontainers usages
    // (Postgres, Wiremock) are unaffected.
}
