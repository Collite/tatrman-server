# verify-public-resolution — the SV-P1 S4 phase-DONE proof

A standalone Gradle project that resolves **every published TTR spine artifact
from Maven Central only** — no GitHub Packages, no credentials. If it resolves,
the `org.tatrman:*` coordinates are genuinely public (RO-17).

## Run

On a machine/container with **no** `~/.gradle` credentials:

```bash
cd scripts/verify-public-resolution
gradle -PspineVersion=0.9.0 verifyPublicResolution --refresh-dependencies
# or inspect the tree:
gradle -PspineVersion=0.9.0 dependencies --configuration spine --refresh-dependencies
```

`--refresh-dependencies` defeats any local cache so this is a true anonymous
fetch. Use a fresh `-g` Gradle home (`gradle -g /tmp/empty-gradle-home …`) to be
certain no cached credentialed copy is used.

## Artifacts checked

The 7 tatrman toolchain modules (`ttr-parser`, `ttr-writer`, `ttr-semantics`,
`ttr-metadata`, `ttr-metadata-git`, `ttr-plan-proto`, `ttr-translator`) and the
11 tatrman-server libs (`ttr-server-proto`, `otel-config`, `logging-config`,
`ktor-configurator`, `db-common`, `data-formatter`, `fuzzy-common`,
`whois-common`, `keycloak-auth`, `ttr-meta-client`, `ttr-llm-client`).

## Versions

The read spine did not publish under one uniform version. Pass what you are
verifying: `-PspineVersion` (default `0.9.0`) covers most; the `ttr-metadata`
pair uses `-PmetadataVersion` (default `0.9.1`). Adjust as the Central line
evolves.

## Caveats

- **Cannot pass until S4 T4/T5 have published to Central.** Until then the
  coordinates only exist on GitHub Packages (credentialed) and this fails by
  design.
- **Central CDN/search sync lags a release by ~15–120 min.** Don't treat a
  failure as real until ~2 h after the portal release.
- Reruns at every future gate and at SV-P6 as the standing public-access proof.
