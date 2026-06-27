# whois-common

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`shared/libs/kotlin/whois-common/`), tag `kantheon-fork-point`, forked 2026-06-24 (fork Phase 5 Stage 5.0).
> Maintained independently since the fork; do not assume parity with the ai-platform original.

The shared domain records for the whois user/role directory: `UserRecord`, `UserIdRecord`,
`UserSource`. Pure `@Serializable` data classes with no infrastructure dependencies — shared
between the `infra/whois` service and any client that reads its JSON (notably Argos's
optional `WhoisRoleSource`, fork Stage 5.3).

## Package root

`org.tatrman.whois.domain` (renamed off `infra.whois.domain` per the technical-wave package
sweep — fork contracts §1).
