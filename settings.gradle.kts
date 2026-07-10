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

// Phase SV-P0 modules — populated as the S3 move lands the spine services.
// (shared/proto, services/*, workers/*, tools/*, infra/* arrive in S3/S4.)
include(":tools:_smoke-test")
