#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-out throughput micro-benchmark for Polars cost_hints (Fork Stage 4.1 T4).

Pushes a 1e5-row table through the worker's real read-out path — a representative
Polars transform, ``df.to_arrow()``, then the worker's own ``_split_table`` +
``_serialize_record_batch`` (Arrow IPC) — and reports rows/s + p50/p95 latency.
The numbers are recorded in ``workers/polars/README.md`` (Polars is a worker;
it has no capability manifest, so the README is the home for the baseline — the
Charon/Metis idiom adapted). Re-run after a dependency bump:

    uv run python bench/bench_throughput.py

Results are indicative, single-host; the DB/source fetch is excluded (that is the
integration-suite's territory). This measures the CPU-bound compute + serialize
read-out that dominates a steady-state worker batch.
"""
from __future__ import annotations

import statistics
import time

import polars as pl

from workers_polars.grpc_service import _serialize_record_batch, _split_table

ROWS = 100_000
BATCH_ROWS = 10_000
WARMUP = 3
REPEATS = 20


def _frame(n: int) -> pl.DataFrame:
    return pl.DataFrame(
        {
            "id": range(n),
            "region": [f"r{i % 8}" for i in range(n)],
            "amount": [float(i) * 1.5 for i in range(n)],
        }
    )


def _readout(df: pl.DataFrame) -> int:
    # Representative compute (a filter + projection) then the worker's exact
    # Arrow read-out: to_arrow -> split into RecordBatches -> IPC-serialize each.
    transformed = df.filter(pl.col("amount") >= 0.0).select(["id", "region", "amount"])
    table = transformed.to_arrow()
    rows = 0
    for batch in _split_table(table, BATCH_ROWS):
        _serialize_record_batch(batch.schema, batch)
        rows += batch.num_rows
    return rows


def main() -> int:
    df = _frame(ROWS)
    for _ in range(WARMUP):
        _readout(df)
    samples_ms: list[float] = []
    rows_seen = 0
    for _ in range(REPEATS):
        t0 = time.monotonic()
        rows_seen = _readout(df)
        samples_ms.append((time.monotonic() - t0) * 1000.0)
    samples_ms.sort()
    p50 = statistics.median(samples_ms)
    p95 = samples_ms[min(len(samples_ms) - 1, int(0.95 * len(samples_ms)))]
    rows_per_s = rows_seen / (p50 / 1000.0)

    print(f"rows           : {rows_seen}")
    print(f"batch_rows     : {BATCH_ROWS}")
    print(f"repeats        : {REPEATS}")
    print(f"p50_ms         : {p50:.1f}")
    print(f"p95_ms         : {p95:.1f}")
    print(f"rows_per_sec   : {rows_per_s:,.0f}  (at p50)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
