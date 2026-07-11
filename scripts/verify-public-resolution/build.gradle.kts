// SV-P1 S4 T6 — the phase-DONE proof: every published spine artifact resolves
// ANONYMOUSLY from Maven Central. Run on a machine/container with NO ~/.gradle
// credentials:
//
//   gradle -PspineVersion=0.9.0 verifyPublicResolution --refresh-dependencies
//   # (or: gradle -PspineVersion=0.9.0 dependencies --configuration spine --refresh-dependencies)
//
// The ONLY repository is mavenCentral() — no GitHub Packages, no credentials.
// If resolution succeeds, the org.tatrman:* coordinates are genuinely public.
//
// NOTE: this cannot pass until S4 T4/T5 have actually published to Central, and
// Central search/CDN sync lags a release by ~15-120 min (don't flag a failure
// before ~2 h). Reruns at every future gate + at SV-P6.
//
// Versions: the read spine did not publish under one uniform version — pass the
// version(s) being verified. `spineVersion` (default 0.9.0) covers most; the
// ttr-metadata pair defaults to `metadataVersion` (default 0.9.1). Override
// either on the command line as the Central line evolves.
plugins { base }

repositories {
    mavenCentral() // NO GitHub Packages, NO credentials — the whole point of the proof.
}

val spineVersion = (findProperty("spineVersion") as String?) ?: "0.9.0"
val metadataVersion = (findProperty("metadataVersion") as String?) ?: "0.9.1"

val spine: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    // tatrman toolchain (Collite/tatrman)
    spine("org.tatrman:ttr-parser:$spineVersion")
    spine("org.tatrman:ttr-writer:$spineVersion")
    spine("org.tatrman:ttr-semantics:$spineVersion")
    spine("org.tatrman:ttr-metadata:$metadataVersion")
    spine("org.tatrman:ttr-metadata-git:$metadataVersion")
    spine("org.tatrman:ttr-plan-proto:$spineVersion")
    spine("org.tatrman:ttr-translator:$spineVersion")
    // tatrman-server libs (Collite/tatrman-server)
    spine("org.tatrman:ttr-server-proto:$spineVersion")
    spine("org.tatrman:otel-config:$spineVersion")
    spine("org.tatrman:logging-config:$spineVersion")
    spine("org.tatrman:ktor-configurator:$spineVersion")
    spine("org.tatrman:db-common:$spineVersion")
    spine("org.tatrman:data-formatter:$spineVersion")
    spine("org.tatrman:fuzzy-common:$spineVersion")
    spine("org.tatrman:whois-common:$spineVersion")
    spine("org.tatrman:keycloak-auth:$spineVersion")
    spine("org.tatrman:ttr-meta-client:$spineVersion")
    spine("org.tatrman:ttr-llm-client:$spineVersion")
}

tasks.register("verifyPublicResolution") {
    val resolved = spine.incoming.artifactView { lenient(false) }.files
    doLast {
        val count = resolved.files.size
        require(count > 0) { "no artifacts resolved — Central publish incomplete or not yet synced" }
        println("✅ resolved $count files anonymously from Maven Central (spine=$spineVersion, metadata=$metadataVersion)")
    }
}
