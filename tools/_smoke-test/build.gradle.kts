// SPDX-License-Identifier: Apache-2.0
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

dependencies {
    implementation(libs.kotlin.stdlib)

    testImplementation(libs.bundles.kotest)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
