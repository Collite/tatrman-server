# otel-config

> **forked-from:** `ai-platform@2575b923dca521fea0e3156257e4b779f02a6ed4` (`shared/libs/kotlin/otel-config/`), tag `kantheon-fork-point`, forked 2026-06-13.
> Maintained independently since the fork; do not assume parity with the ai-platform original.

Initialises the OpenTelemetry SDK from HOCON config: OTLP exporters (traces, metrics, logs), the SLF4J/Logback bridge so log records flow into the same `SdkLoggerProvider`, and the resource attributes (service.name, service.version, kantheon module tag).

Used by every Kotlin service via `createOpenTelemetrySdk(...)`. See `EXAMPLES.md` §8 for the canonical init pattern.

## Package root

`shared.otel` (preserved from ai-platform per the fork convention — see [`tasks-p1-s1.3-shared-libs.md`](../../../docs/implementation/v1/fork/tasks-p1-s1.3-shared-libs.md) pre-flight note).
