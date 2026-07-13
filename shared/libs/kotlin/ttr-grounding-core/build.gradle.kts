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
    // plan.v1 Expression / ParameterBinding — the recipe scaffolding the kernel renders.
    api(project(":shared:proto"))
    // The S-2 shared fold (diacritic-insensitive normalization) the recognizers call.
    api(project(":shared:libs:kotlin:ttr-text"))

    testImplementation(libs.bundles.kotest)
    // JsonFormat — the characterization golden serialises each Expression tree.
    testImplementation(libs.protobuf.java.util)
    // Runtime JSON-tree parsing for the golden JSONL (no @Serializable classes → no compiler plugin).
    testImplementation(libs.kotlinx.serialization.json)
}
