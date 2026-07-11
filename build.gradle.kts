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

// ── SV-P0 interim publishing (S5 wrinkle) ────────────────────────────────────
// The shared libs + proto stubs moved from kantheon, but kantheon's staying
// agents/tools/services still consume them (otel-config, logging-config,
// ktor-configurator, the client libs, the spine proto stubs, …). They publish
// `0.0.1-LOCAL` to Maven Local so kantheon resolves them as `org.tatrman:*`
// artifacts — the SAME mechanism kantheon already uses for `ttr-metadata`
// (standing facts). Flips to the 0.9.x public line at SV-P1 gate 3. Test-support
// libs (component-testkit, integration-harness) are server-internal → not published.
val publishableLibs =
    setOf(
        ":shared:proto",
        ":shared:libs:kotlin:otel-config",
        ":shared:libs:kotlin:logging-config",
        ":shared:libs:kotlin:ktor-configurator",
        ":shared:libs:kotlin:db-common",
        ":shared:libs:kotlin:data-formatter",
        ":shared:libs:kotlin:fuzzy-common",
        ":shared:libs:kotlin:whois-common",
        ":shared:libs:kotlin:keycloak-auth",
        ":shared:libs:kotlin:ttr-meta-client",
        ":shared:libs:kotlin:ttr-llm-client",
        // capabilities-client TRIMMED at SV-P1 S2 (2026-07-11): no external consumer
        // (kantheon keeps its own copy; nothing published depends on it). Publishing it
        // would be a maintenance promise with no taker — re-add here if a consumer appears.
    )
subprojects {
    if (path !in publishableLibs) return@subprojects
    apply(plugin = "maven-publish")
    afterEvaluate {
        extensions.configure<org.gradle.api.publish.PublishingExtension> {
            publications {
                create<org.gradle.api.publish.maven.MavenPublication>("maven") {
                    from(components["java"])
                    // group inherited (org.tatrman); version inherited; artifactId = project.name,
                    // except shared/proto whose bare name "proto" is too generic a coordinate.
                    if (project.path == ":shared:proto") artifactId = "ttr-server-proto"
                    pom {
                        url.set("https://github.com/Collite/tatrman-server")
                        licenses {
                            license {
                                name.set("Apache-2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                            }
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/Collite/tatrman-server")
                    credentials {
                        username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                        password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}
