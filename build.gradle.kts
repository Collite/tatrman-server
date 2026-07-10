plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
}

allprojects {
    // The open-spine group id: plain `org.tatrman` (kantheon's own group carries
    // an extra segment; server artifacts do not). The license/ownership boundary
    // is physical — server artifacts live under org.tatrman:* the same as the
    // tatrman toolchain (contracts §1, §7).
    group = "org.tatrman"
    version = providers.gradleProperty("tatrman-server.version").orNull ?: "0.0.0-SNAPSHOT"
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }

        // ── Component test tier (mirrors kantheon's testing arc) ─────────────
        // A dedicated `componentTest` source set (`src/componentTest/kotlin`)
        // for **real-dependency** Testcontainers specs, physically separate from
        // `src/test` so the `test` PR gate stays mocked-only. Kotest ships its
        // own JUnit-Platform engine; isolation from the mocked `test` gate is the
        // source-set split plus the classpath guard below.
        extensions.configure<org.gradle.testing.base.TestingExtension> {
            suites.register("componentTest", org.gradle.api.plugins.jvm.JvmTestSuite::class.java) {
                dependencies {
                    implementation(project())
                    implementation(libs.kotest.runner.junit5)
                    implementation(libs.kotest.assertions.core)
                    implementation(libs.kotest.property)
                    implementation(libs.testcontainers)
                }
                targets.configureEach {
                    testTask.configure {
                        useJUnitPlatform()
                        shouldRunAfter(tasks.named("test"))
                    }
                }
            }

            // ── Integration test tier (cluster-bound; skips without -Pcontext) ─
            suites.register("integrationTest", org.gradle.api.plugins.jvm.JvmTestSuite::class.java) {
                dependencies {
                    implementation(project())
                    implementation(libs.kotest.runner.junit5)
                    implementation(libs.kotest.assertions.core)
                    implementation(libs.kotest.property)
                }
                targets.configureEach {
                    testTask.configure {
                        useJUnitPlatform()
                        shouldRunAfter(tasks.named("test"))
                        onlyIf { providers.gradleProperty("context").isPresent }
                        providers.gradleProperty("context").orNull?.let { systemProperty("context", it) }
                        providers.gradleProperty("namespace").orNull?.let { systemProperty("namespace", it) }
                    }
                }
            }
        }

        // The unit `test` gate must never run a component/integration spec.
        // Fail fast if either higher tier's output leaks onto the unit classpath.
        val higherTierClassesDirs =
            extensions.getByType<org.gradle.api.tasks.SourceSetContainer>().let { sets ->
                sets["componentTest"].output.classesDirs + sets["integrationTest"].output.classesDirs
            }
        tasks.named<org.gradle.api.tasks.testing.Test>("test") {
            doFirst {
                val higherTierFiles = higherTierClassesDirs.files
                val leaked = classpath.files.filter { it in higherTierFiles }
                require(leaked.isEmpty()) {
                    "Higher-tier (component/integration) classes on the unit `test` classpath: $leaked. " +
                        "test-all must stay mocked-only."
                }
            }
        }
    }

    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        // ktlint config picked up from .editorconfig at repo root.
    }
}
