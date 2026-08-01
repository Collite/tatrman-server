// SPDX-License-Identifier: Apache-2.0
package org.tatrman.charon.core

/**
 * The four `Location` kinds Charon knows in v1 (charon/contracts.md §1).
 * Adding a new kind (Parquet-on-S3, DuckDB worker, etc.) means:
 *   1. a new `Location.kind` field in the proto,
 *   2. a new enum value here, and
 *   3. one new row in the `legality` matrix (`Legality.kt`, service-side).
 *
 * Part of the published seam (`transfer-core`, CH-D5): [CharonError] variants
 * name the kind, so it travels with the error surface consumers pattern-match on.
 */
enum class LocationKind {
    SEAWEED,
    REDIS,
    WORKER_DF,
    DB_TABLE,
}
