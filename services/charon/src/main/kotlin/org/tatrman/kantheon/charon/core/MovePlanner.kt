package org.tatrman.kantheon.charon.core

import org.tatrman.charon.v1.CopyRequest
import org.tatrman.charon.v1.DbWriteMode
import org.tatrman.charon.v1.DescribeRequest
import org.tatrman.charon.v1.EvictRequest
import org.tatrman.charon.v1.Location
import org.tatrman.charon.v1.MaterializeRequest
import org.tatrman.charon.v1.MoveOptions
import org.tatrman.charon.v1.StageRequest

/**
 * A [Plan] is what the planner hands to the [MoveExecutor] when the legality
 * check passes. The executor still has to *do* the move — the plan is the
 * validated spec, not the result.
 *
 * Kept as a small data class (one public class per file, `AGENTS.md` §9) —
 * additional fields land in later stages (the connection registry handle, the
 * resolved worker endpoint address, the per-RPC deadline, etc.).
 */
data class Plan(
    val rpc: MoveRpc,
    val source: Location,
    val target: Location?,
    val options: MoveOptions,
)

/**
 * The pure planning layer. No I/O, no coroutines, no service deps.
 *
 * The single entry point [plan] takes the raw proto request, classifies the
 * `(rpc, source, target)` tuple against the [legality] matrix, and returns
 * either a [Plan] (executor-bound) or a non-empty list of [CharonError]s
 * (request-shape problems the gRPC layer turns into `INVALID_ARGUMENT` /
 * `FAILED_PRECONDITION` per the [CharonError.toStatus] mapping).
 *
 * Per `AGENTS.md` §9 ("Result + error type pairs"): a `Result`-shaped sealed
 * outcome would be one option, but the planner needs to return *multiple*
 * errors (e.g. an `IllegalPair` AND a `MissingOrInvalidDbWriteMode` for the
 * same request), so an `Either<List<CharonError>, Plan>` idiom is more honest.
 * We spell that out as a small `sealed` `Planned` union below.
 */
class MovePlanner {
    fun plan(req: MaterializeRequest): Planned =
        planMove(
            rpc = MoveRpc.MATERIALIZE,
            source = req.source,
            target = req.target,
            options = req.options,
        )

    fun plan(req: StageRequest): Planned =
        planMove(
            rpc = MoveRpc.STAGE,
            source = req.source,
            target = req.target.toLocation(),
            options = req.options,
        )

    fun plan(req: CopyRequest): Planned =
        planMove(
            rpc = MoveRpc.COPY,
            source = req.source,
            target = req.target,
            options = req.options,
        )

    fun plan(req: EvictRequest): Planned {
        // Evict has no `target` and no `options` — but the legality matrix
        // still applies. The matrix cell is `(rpc=EVICT, source, target=source)`,
        // because evict is a unary verb on a single location. The
        // `IllegalTargetForRpc` cross-check still applies (no `Evict` on DBs).
        if (req.location.kindCase == Location.KindCase.KIND_NOT_SET) {
            return Planned.Invalid(listOf(CharonError.EmptyLocation(MoveRpc.EVICT, "source")))
        }
        val sourceKind = req.location.kind()
        val errors = mutableListOf<CharonError>()
        if (req.location.hasDbTable()) {
            val db = req.location.dbTable
            errors +=
                CharonError.CannotEvictDbTable(
                    connectionId = db.connectionId,
                    schema = db.schema,
                    table = db.table,
                )
        }
        val cell = legalityOf(MoveRpc.EVICT, sourceKind, sourceKind)
        if (cell == Legality.DISALLOWED) {
            errors += CharonError.IllegalPair(MoveRpc.EVICT, sourceKind, sourceKind)
        }
        if (errors.isNotEmpty()) return Planned.Invalid(errors)
        // `SAME_LOCATION` is meaningless for an evict — the planner still
        // returns a Plan so the executor can run the delete (idempotent).
        return Planned.Plan(
            Plan(
                rpc = MoveRpc.EVICT,
                source = req.location,
                target = null,
                options = MoveOptions.getDefaultInstance(),
            ),
        )
    }

    fun plan(req: DescribeRequest): Planned {
        // Describe is read-only; legality is always ALLOWED. We still hand
        // back a Plan so the executor seam is uniform.
        return Planned.Plan(
            Plan(
                rpc = MoveRpc.DESCRIBE,
                source = req.location,
                target = null,
                options = MoveOptions.getDefaultInstance(),
            ),
        )
    }

    private fun planMove(
        rpc: MoveRpc,
        source: Location,
        target: Location,
        options: MoveOptions,
    ): Planned {
        // Guard empty (default) locations before `kind()` — an unset
        // `oneof` would otherwise throw (review-006 R8.7 / L5).
        if (source.kindCase == Location.KindCase.KIND_NOT_SET) {
            return Planned.Invalid(listOf(CharonError.EmptyLocation(rpc, "source")))
        }
        if (target.kindCase == Location.KindCase.KIND_NOT_SET) {
            return Planned.Invalid(listOf(CharonError.EmptyLocation(rpc, "target")))
        }
        val errors = mutableListOf<CharonError>()
        val sourceKind = source.kind()
        val targetKind = target.kind()

        // 1. Cross-check the RPC's target constraint (independent of the
        //    legality matrix — `Materialize` may NEVER carry a worker
        //    session as target; `Stage` may ONLY carry one).
        if (!isTargetValidForRpc(rpc, targetKind)) {
            errors += CharonError.IllegalTargetForRpc(rpc, targetKind)
        }

        // 2. Look up the cell in the matrix.
        val cell = legalityOf(rpc, sourceKind, targetKind)
        when (cell) {
            null -> errors += CharonError.IllegalPair(rpc, sourceKind, targetKind)
            Legality.DISALLOWED -> errors += CharonError.IllegalPair(rpc, sourceKind, targetKind)
            Legality.ALLOWED, Legality.SAME_LOCATION -> Unit
        }

        // 3. DB-target write-mode cross-check: required when target is a
        //    `DbTable`, must NOT be set when it isn't (caller's typo).
        if (targetKind == LocationKind.DB_TABLE) {
            if (!options.hasDbWriteMode() || options.dbWriteMode == DbWriteMode.DB_WRITE_MODE_UNSPECIFIED) {
                errors += CharonError.MissingOrInvalidDbWriteMode(targetKind)
            }
        } else if (options.hasDbWriteMode() && options.dbWriteMode != DbWriteMode.DB_WRITE_MODE_UNSPECIFIED) {
            errors += CharonError.MissingOrInvalidDbWriteMode(targetKind)
        }

        if (errors.isNotEmpty()) return Planned.Invalid(errors)
        return Planned.Plan(Plan(rpc, source, target, options))
    }

    private fun isTargetValidForRpc(
        rpc: MoveRpc,
        target: LocationKind,
    ): Boolean =
        when (rpc) {
            MoveRpc.MATERIALIZE ->
                target == LocationKind.SEAWEED ||
                    target == LocationKind.REDIS ||
                    target == LocationKind.DB_TABLE
            MoveRpc.STAGE -> target == LocationKind.WORKER_DF
            MoveRpc.COPY -> true
            MoveRpc.EVICT -> true // enforced separately in `plan(EvictRequest)`
            MoveRpc.DESCRIBE -> true
        }
}

/** The planner's outcome. `Invalid` carries one-or-more errors; `Plan` is
 *  the validated spec the executor runs. */
sealed class Planned {
    data class Invalid(
        val errors: List<CharonError>,
    ) : Planned()

    data class Plan(
        val plan: org.tatrman.kantheon.charon.core.Plan,
    ) : Planned()
}

/** Classify a [Location] into the [LocationKind] Charon reasons about.
 *  Exhaustive over the proto's `oneof kind` (the compiler enforces it). */
fun Location.kind(): LocationKind =
    when {
        hasSeaweed() -> LocationKind.SEAWEED
        hasRedis() -> LocationKind.REDIS
        hasWorkerDf() -> LocationKind.WORKER_DF
        hasDbTable() -> LocationKind.DB_TABLE
        else -> error("Location has no kind set: $this")
    }

/** Promote a proto `WorkerSessionDf` to a `Location` (used by `Stage`'s typed
 *  request — the worker target is a `WorkerSessionDf`, not a `Location` on the
 *  wire). */
private fun org.tatrman.charon.v1.WorkerSessionDf.toLocation(): Location =
    Location.newBuilder().setWorkerDf(this).build()
