// SPDX-License-Identifier: Apache-2.0
rootProject.name = "tatrman-server"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // TEMPORARY (SV-P0/P1 interim): consume the tatrman `org.tatrman:*`
        // artifacts (ttr-metadata, ttr-plan-proto, …) at `0.0.1-LOCAL` from
        // Maven Local while the fork settles. Removed once the SV-P1 publish
        // gates land the 0.9.x line on the public registry (plan §SV-P1).
        mavenLocal()
        // TTR toolchain (org.tatrman:ttr-{parser,writer,semantics,metadata,…}),
        // published by the `tatrman` repo to GitHub Packages under `Collite/tatrman`.
        // These are NOT on Maven Central yet; the same per-user `gpr.*` PAT that
        // kantheon uses authenticates here. `includeGroup("org.tatrman")` keeps the
        // repo scoped to that group only.
        maven {
            name = "Tatrman"
            url = uri("https://maven.pkg.github.com/Collite/tatrman")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
            content {
                includeGroup("org.tatrman")
            }
        }
    }
}

// Toolchain exercise module (S1) — deleted once the moved modules are green.
include(":tools:_smoke-test")

// ── SV-P0 S3 move set — spine transplanted from kantheon@355c68d, renamed on
// arrival (ledger §3). Package/proto internals are swept in S4; the build is
// intentionally RED until then (S3+S4 = one change window).
// Python modules (services/ttr-nlp, workers/ttr-worker-polars) and the non-Gradle
// infra/backstage are built out-of-band, not included here.

// Shared wire protos + Kotlin libs
include(":shared:proto")
include(":shared:libs:kotlin:otel-config")
include(":shared:libs:kotlin:logging-config")
include(":shared:libs:kotlin:ktor-configurator")
include(":shared:libs:kotlin:db-common")
include(":shared:libs:kotlin:data-formatter")
include(":shared:libs:kotlin:fuzzy-common")
include(":shared:libs:kotlin:whois-common")
include(":shared:libs:kotlin:keycloak-auth")
include(":shared:libs:kotlin:ttr-meta-client")
include(":shared:libs:kotlin:ttr-llm-client")
// Grafted from kantheon per Bora's decision (S4) — capability-registration client
// (4 MCP tools) + the component/integration test-tier harness libs.
include(":shared:libs:kotlin:capabilities-client")
include(":shared:libs:kotlin:component-testkit")
include(":shared:libs:kotlin:integration-harness")

// Spine services
include(":services:veles")
include(":services:ttr-query")
include(":services:ttr-translate")
include(":services:ttr-validate")
include(":services:ttr-dispatch")
include(":services:ttr-fuzzy")
include(":services:ttr-llm-gateway")

// Engine workers (JVM; the Polars worker is Python — out of the Gradle build)
include(":workers:ttr-worker-postgres")
include(":workers:ttr-worker-mssql")

// MCP tools
include(":tools:ttr-meta-mcp")
include(":tools:ttr-query-mcp")
include(":tools:ttr-fuzzy-mcp")
include(":tools:ttr-nlp-mcp")

// Infra (RO-22: health + backstage ride the server repo)
include(":infra:ttr-identity")
include(":infra:health")
