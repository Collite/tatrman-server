// SPDX-License-Identifier: Apache-2.0
// :shared:proto-llm — the LLM gateway wire contract (org.tatrman.llm.v1), split
// out of the monolithic :shared:proto so the generated LlmGatewayService gRPC
// STUB reaches ONLY the modules that legitimately need it (the ttr-llm-gateway
// server). Zero-LLM services (e.g. ttr-resolver, RS-23) depend on :shared:proto
// and therefore no longer receive a callable LLM client stub on their classpath.
//
// This module deliberately mirrors :shared:proto's protobuf config MINUS the
// Python builtin (no Python consumer of llm.v1) and MINUS the plan-proto include
// path (llm_gateway.proto imports no in-repo proto — only google/protobuf/struct).
import com.google.protobuf.gradle.*

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.ktlint)
}

val protobufVersion =
    libs.versions.protobuf
        .asProvider()
        .get()

dependencies {
    api(libs.protobuf.java)
    api(libs.protobuf.kotlin)
    api(libs.grpc.stub)
    api(libs.grpc.protobuf)
    api(libs.grpc.kotlin.stub)
    // Generated grpc-kotlin stubs reference kotlinx.coroutines.flow.Flow.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.bundles.kotest)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.78.0"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.5.0:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("kotlin") { }
            }
            task.plugins {
                id("grpc")
                id("grpckt")
            }
        }
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

ktlint {
    // Generated proto Kotlin sources are not human-authored — exclude.
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}
