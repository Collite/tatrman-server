# db-common

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`shared/libs/kotlin/db-common/`), tag `kantheon-fork-point`, forked 2026-06-13.
> Maintained independently since the fork; do not assume parity with the ai-platform original.

HikariCP + Exposed bootstrap for MSSQL/Postgres, with HOCON-driven `DatabaseConfig` and a `DatabaseConnection` lifecycle owner. Used by `workers/brontes` (MSSQL), `services/argos` and `services/theseus` (Postgres).

## Package root

`shared.libs.db.common` (preserved from ai-platform per the fork convention — see [`tasks-p1-s1.3-shared-libs.md`](../../../docs/implementation/v1/fork/tasks-p1-s1.3-shared-libs.md) pre-flight note).
