package org.tatrman.kantheon.charon.core

import org.tatrman.kantheon.charon.core.LocationKind.DB_TABLE
import org.tatrman.kantheon.charon.core.LocationKind.REDIS
import org.tatrman.kantheon.charon.core.LocationKind.SEAWEED
import org.tatrman.kantheon.charon.core.LocationKind.WORKER_DF
import org.tatrman.kantheon.charon.core.MoveRpc.COPY
import org.tatrman.kantheon.charon.core.MoveRpc.DESCRIBE
import org.tatrman.kantheon.charon.core.MoveRpc.EVICT
import org.tatrman.kantheon.charon.core.MoveRpc.MATERIALIZE
import org.tatrman.kantheon.charon.core.MoveRpc.STAGE

/**
 * The five RPCs Charon exposes. The legality matrix (below) is keyed by an
 * `MoveRpc` value, not a string, so adding a new RPC is a compile error in
 * the matrix (and a new row, not a hand-edit on the wire).
 *
 * See `docs/architecture/charon/contracts.md` §1 for the proto-side definition
 * of the same operations.
 */
enum class MoveRpc {
    MATERIALIZE,
    STAGE,
    COPY,
    EVICT,
    DESCRIBE,
}

/**
 * The four `Location` kinds Charon knows in v1 (charon/contracts.md §1).
 * Adding a new kind (Parquet-on-S3, DuckDB worker, etc.) means:
 *   1. a new `Location.kind` field in the proto,
 *   2. a new enum value here, and
 *   3. one new row in the `legality` matrix.
 */
enum class LocationKind {
    SEAWEED,
    REDIS,
    WORKER_DF,
    DB_TABLE,
}

/**
 * The disposition of a `(rpc, source, target)` tuple:
 *   - [ALLOWED]       — the move is permitted; the planner assembles a `Plan`.
 *   - [DISALLOWED]    — the move is forbidden by the v1 contract; surfaces
 *                       as `INVALID_ARGUMENT` with a Rule-6 hint naming the
 *                       illegal pair.
 *   - [SAME_LOCATION] — source and target resolve to the same physical
 *                       object; the move is a documented no-op (a `MoveResult`
 *                       with a Rule-6 note, no I/O — contracts §2 last row).
 *
 * [SAME_LOCATION] is its own outcome (not collapsed into [ALLOWED]) because
 * it changes the planner's behaviour: there is no pipe to assemble, no
 * fingerprint to verify, no `MoveExecutor` to invoke.
 */
enum class Legality {
    ALLOWED,
    DISALLOWED,
    SAME_LOCATION,
}

/**
 * The single source of truth for the v1 legality table.
 *
 * The [docs/architecture/charon/contracts.md §2] table is hand-derived from
 * this matrix (not the other way around) — a disagreement between the table
 * and the matrix is a spec bug, and the table must be regenerated. The unit
 * suite `MovePlannerSpec` walks the entire matrix and asserts each cell, so
 * any drift fails the build.
 *
 * Rules baked in (per contracts §1 + §2):
 *
 *   - `Materialize` target is `SEAWEED | REDIS | DB_TABLE`; never a worker
 *     session (that's `Stage`).
 *   - `Stage` target is exactly `WORKER_DF`; no other kind.
 *   - `Evict` is permitted on any kind **except `DB_TABLE`** (DB cleanup is
 *     the owner's job, not Charon's).
 *   - `Describe` is read-only and is permitted for every kind.
 *   - `Copy` is the generic verb — permitted for any legal `(source, target)`
 *     pair the matrix marks [ALLOWED] below, with the same-location carve-out.
 *   - The same-location no-op is its own outcome; it doesn't go through
 *     `MoveExecutor` and doesn't open a pipe.
 */
@Suppress("EnumValuesSoftDeprecatedAccess")
internal val legality: Map<MoveRpc, Map<LocationKind, Map<LocationKind, Legality>>> =
    mapOf(
        MATERIALIZE to
            mapOf(
                SEAWEED to
                    mapOf(
                        SEAWEED to Legality.SAME_LOCATION,
                        REDIS to Legality.ALLOWED,
                        WORKER_DF to Legality.DISALLOWED,
                        DB_TABLE to Legality.ALLOWED,
                    ),
                REDIS to
                    mapOf(
                        SEAWEED to Legality.ALLOWED,
                        REDIS to Legality.SAME_LOCATION,
                        WORKER_DF to Legality.DISALLOWED,
                        DB_TABLE to Legality.ALLOWED,
                    ),
                WORKER_DF to
                    mapOf(
                        SEAWEED to Legality.ALLOWED,
                        REDIS to Legality.ALLOWED,
                        WORKER_DF to Legality.SAME_LOCATION,
                        DB_TABLE to Legality.ALLOWED,
                    ),
                DB_TABLE to
                    mapOf(
                        SEAWEED to Legality.ALLOWED,
                        REDIS to Legality.ALLOWED,
                        WORKER_DF to Legality.DISALLOWED,
                        // db→db is Copy-only (cross-connection); Materialize is
                        // for caching into a blob/redis tier (contracts §2).
                        DB_TABLE to Legality.DISALLOWED,
                    ),
            ),
        STAGE to
            mapOf(
                SEAWEED to
                    mapOf(
                        WORKER_DF to Legality.ALLOWED,
                        SEAWEED to Legality.DISALLOWED,
                        REDIS to Legality.DISALLOWED,
                        DB_TABLE to Legality.DISALLOWED,
                    ),
                REDIS to
                    mapOf(
                        WORKER_DF to Legality.ALLOWED,
                        SEAWEED to Legality.DISALLOWED,
                        REDIS to Legality.DISALLOWED,
                        DB_TABLE to Legality.DISALLOWED,
                    ),
                WORKER_DF to
                    mapOf(
                        WORKER_DF to Legality.SAME_LOCATION,
                        SEAWEED to Legality.DISALLOWED,
                        REDIS to Legality.DISALLOWED,
                        DB_TABLE to Legality.DISALLOWED,
                    ),
                DB_TABLE to
                    mapOf(
                        WORKER_DF to Legality.ALLOWED,
                        SEAWEED to Legality.DISALLOWED,
                        REDIS to Legality.DISALLOWED,
                        DB_TABLE to Legality.DISALLOWED,
                    ),
            ),
        COPY to
            mapOf(
                SEAWEED to
                    mapOf(
                        SEAWEED to Legality.SAME_LOCATION,
                        REDIS to Legality.ALLOWED,
                        WORKER_DF to Legality.ALLOWED,
                        DB_TABLE to Legality.ALLOWED,
                    ),
                REDIS to
                    mapOf(
                        SEAWEED to Legality.ALLOWED,
                        REDIS to Legality.SAME_LOCATION,
                        WORKER_DF to Legality.ALLOWED,
                        DB_TABLE to Legality.ALLOWED,
                    ),
                WORKER_DF to
                    mapOf(
                        SEAWEED to Legality.ALLOWED,
                        REDIS to Legality.ALLOWED,
                        WORKER_DF to Legality.SAME_LOCATION,
                        DB_TABLE to Legality.ALLOWED,
                    ),
                DB_TABLE to
                    mapOf(
                        SEAWEED to Legality.ALLOWED,
                        REDIS to Legality.ALLOWED,
                        WORKER_DF to Legality.ALLOWED,
                        DB_TABLE to Legality.ALLOWED,
                    ),
            ),
        EVICT to
            mapOf(
                SEAWEED to
                    mapOf(
                        SEAWEED to Legality.ALLOWED,
                        REDIS to Legality.DISALLOWED,
                        WORKER_DF to Legality.DISALLOWED,
                        DB_TABLE to Legality.DISALLOWED,
                    ),
                REDIS to
                    mapOf(
                        SEAWEED to Legality.DISALLOWED,
                        REDIS to Legality.ALLOWED,
                        WORKER_DF to Legality.DISALLOWED,
                        DB_TABLE to Legality.DISALLOWED,
                    ),
                WORKER_DF to
                    mapOf(
                        SEAWEED to Legality.DISALLOWED,
                        REDIS to Legality.DISALLOWED,
                        WORKER_DF to Legality.ALLOWED,
                        DB_TABLE to Legality.DISALLOWED,
                    ),
                DB_TABLE to
                    mapOf(
                        SEAWEED to Legality.DISALLOWED,
                        REDIS to Legality.DISALLOWED,
                        WORKER_DF to Legality.DISALLOWED,
                        DB_TABLE to Legality.DISALLOWED,
                    ),
            ),
        DESCRIBE to
            mapOf(
                SEAWEED to allKinds(),
                REDIS to allKinds(),
                WORKER_DF to allKinds(),
                DB_TABLE to allKinds(),
            ),
    )

/** `Describe` is read-only — every source kind permits every target (a no-op
 *  argument; we keep the matrix uniform). Returns a map of
 *  `target -> ALLOWED` for all four kinds so a missing cell is a compile error
 *  if a new kind is added. */
private fun allKinds(): Map<LocationKind, Legality> = LocationKind.entries.associateWith { Legality.ALLOWED }

/** Public, read-only lookup. Returns `null` when the cell is absent — that
 *  itself is a programming error in the matrix (a missing cell means the
 *  matrix wasn't updated when a new kind landed) and the planner surfaces it
 *  as `INVALID_ARGUMENT` rather than throwing. */
fun legalityOf(
    rpc: MoveRpc,
    source: LocationKind,
    target: LocationKind,
): Legality? = legality[rpc]?.get(source)?.get(target)
