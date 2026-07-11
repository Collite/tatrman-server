# data-formatter

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`shared/libs/kotlin/data-formatter/`), tag `kantheon-fork-point`, forked 2026-06-13.
> Maintained independently since the fork; do not assume parity with the ai-platform original.

Arrow IPC + Apache POI (XLSX) + Apache Parquet output formatters over a canonical `RowBatch`. Snapshot-driven, format-agnostic — emit CSV/TSV/JSON/Markdown/XLSX/Parquet from the same in-memory representation, used by `tools/ttr-query-mcp` and the workers layer for export.

## Package root

`shared.formatter` (preserved from ai-platform per the fork convention — see [`tasks-p1-s1.3-shared-libs.md`](../../../docs/implementation/v1/fork/tasks-p1-s1.3-shared-libs.md) pre-flight note).
