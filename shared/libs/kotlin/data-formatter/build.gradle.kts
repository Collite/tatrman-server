// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Arrow's allocator needs these on JDK 17+.
    jvmArgs(
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
}

dependencies {
    api(libs.arrow.vector)
    api(libs.arrow.memory.netty)
    api(libs.kotlinx.serialization.json)
    api(project(":shared:proto"))

    implementation(libs.slf4j.api)
    // SXSSF (streaming) workbook is used by `XlsxWriter` so a
    // few-thousand-row export stays bounded in memory.
    implementation(libs.apache.poi.ooxml)
    // Parquet output — parquet-avro provides the writer ergonomics on top of an
    // Avro schema; parquet-hadoop's `ParquetWriter` underneath. We write to an
    // in-memory OutputFile wrapper (see `ParquetWriter.kt`), so no temp files.
    implementation(libs.apache.parquet.avro)
    implementation(libs.apache.parquet.hadoop)
    implementation(libs.hadoop.common)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    // parquet-avro's reader needs hadoop-mapreduce on the classpath for its
    // `FileInputFormat` reference, even though our in-memory InputFile doesn't actually use it.
    testImplementation(libs.hadoop.mapreduce.client.core)
}
