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
include(":shared:libs:kotlin:ttr-meta-client")   // was ariadne-client
include(":shared:libs:kotlin:ttr-llm-client")    // was llm-gateway-client

// Spine services
include(":services:veles")            // was ariadne
include(":services:ttr-query")        // was theseus
include(":services:ttr-translate")    // was proteus
include(":services:ttr-validate")     // was argos
include(":services:ttr-dispatch")     // was kyklop
include(":services:ttr-fuzzy")        // was echo
include(":services:ttr-llm-gateway")  // was prometheus

// Engine workers (JVM; the Polars worker is Python — out of the Gradle build)
include(":workers:ttr-worker-postgres")  // was arges
include(":workers:ttr-worker-mssql")     // was brontes

// MCP tools
include(":tools:ttr-meta-mcp")    // was ariadne-mcp
include(":tools:ttr-query-mcp")   // was theseus-mcp
include(":tools:ttr-fuzzy-mcp")   // was echo-mcp
include(":tools:ttr-nlp-mcp")     // was kadmos-mcp

// Infra (RO-22: health + backstage ride the server repo)
include(":infra:ttr-identity")    // was whois
include(":infra:health")
