// SPDX-License-Identifier: Apache-2.0
plugins {
    alias(libs.plugins.kotlin.jvm)
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
    // The transfer.v1 / common.v1 wire types the seam exposes (MoveResult / EvictResult /
    // DescribeResult / ResponseMessage / Location / MoveOptions) live in the published
    // `ttr-server-proto`. `api` so an in-process consumer (radegast's CharonMover) gets
    // them transitively — the whole point of this lib is the seam surface, proto included.
    api(project(":shared:proto"))
    // CharonError.toStatus() returns io.grpc.Status (contracts §1 error model) — a public
    // signature type, so `api`. (Also reaches us transitively via :shared:proto's grpc-stub,
    // but the seam depends on it directly, so declare it directly.)
    api(libs.grpc.stub)

    // ConnectionSecretResolver.discover() logs which resolver won (env default vs a
    // ServiceLoader-registered adapter) — an operator diagnostic, internal to the port.
    implementation(libs.slf4j.api)

    testImplementation(libs.bundles.kotest)
}
