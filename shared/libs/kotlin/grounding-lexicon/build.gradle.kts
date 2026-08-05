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
    // RV-P1.6 T4 (RV-42) — the compiled lexicon archive: ttr-snapshot untars it, ttr-lexicon
    // decodes it. Exactly the RV-P1.4 dependency pair lex-matcher took, and for the same reason:
    // reading the artifact must not drag the COMPILER into a serving image.
    api(libs.tatrman.ttr.lexicon)
    implementation(libs.tatrman.ttr.snapshot)
    // The S-2 shared fold — a trigger match is diacritic-insensitive, like every other match.
    api(project(":shared:libs:kotlin:text"))
    implementation(libs.slf4j.api)

    testImplementation(libs.bundles.kotest)
}
