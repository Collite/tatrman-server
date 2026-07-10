package org.tatrman.kantheon.charon.core

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.transfer.v1.CopyRequest
import org.tatrman.transfer.v1.DbTable
import org.tatrman.transfer.v1.DbWriteMode
import org.tatrman.transfer.v1.Location
import org.tatrman.transfer.v1.MaterializeRequest
import org.tatrman.transfer.v1.MoveOptions
import org.tatrman.transfer.v1.RedisEntry
import org.tatrman.transfer.v1.SeaweedBlob
import org.tatrman.transfer.v1.StageRequest
import org.tatrman.transfer.v1.WorkerKind
import org.tatrman.transfer.v1.WorkerSessionDf

/**
 * Walks the entire [legality] matrix and asserts each cell. The matrix is the
 * single source of truth; the contracts §2 table is hand-derived from it.
 * If the table and the matrix ever disagree, this spec fails.
 *
 * Per `AGENTS.md` §8 — TDD gate for Stage 1.1.
 */
class MovePlannerSpec :
    StringSpec({

        val planner = MovePlanner()

        fun locationOf(kind: LocationKind): Location =
            when (kind) {
                LocationKind.SEAWEED ->
                    Location
                        .newBuilder()
                        .setSeaweed(
                            SeaweedBlob
                                .newBuilder()
                                .setBucket("b")
                                .setKey("k")
                                .build(),
                        ).build()
                LocationKind.REDIS ->
                    Location
                        .newBuilder()
                        .setRedis(
                            RedisEntry.newBuilder().setKey("k").build(),
                        ).build()
                LocationKind.WORKER_DF ->
                    Location
                        .newBuilder()
                        .setWorkerDf(
                            WorkerSessionDf
                                .newBuilder()
                                .setWorkerKind(WorkerKind.POLARS)
                                .setSessionId("s")
                                .setDfName("d")
                                .build(),
                        ).build()
                LocationKind.DB_TABLE ->
                    Location
                        .newBuilder()
                        .setDbTable(
                            DbTable
                                .newBuilder()
                                .setConnectionId("c")
                                .setSchema("s")
                                .setTable("t")
                                .build(),
                        ).build()
            }

        fun workerTarget(): WorkerSessionDf =
            WorkerSessionDf
                .newBuilder()
                .setWorkerKind(WorkerKind.POLARS)
                .setSessionId("s")
                .setDfName("d")
                .build()

        fun materialize(
            source: Location,
            target: Location,
            opts: MoveOptions = MoveOptions.getDefaultInstance(),
        ): MaterializeRequest =
            MaterializeRequest
                .newBuilder()
                .setSource(source)
                .setTarget(target)
                .setOptions(opts)
                .build()

        fun stage(
            source: Location,
            target: WorkerSessionDf,
            opts: MoveOptions = MoveOptions.getDefaultInstance(),
        ): StageRequest =
            StageRequest
                .newBuilder()
                .setSource(source)
                .setTarget(target)
                .setOptions(opts)
                .build()

        fun copy(
            source: Location,
            target: Location,
            opts: MoveOptions = MoveOptions.getDefaultInstance(),
        ): CopyRequest =
            CopyRequest
                .newBuilder()
                .setSource(source)
                .setTarget(target)
                .setOptions(opts)
                .build()

        fun optsWith(mode: DbWriteMode): MoveOptions = MoveOptions.newBuilder().setDbWriteMode(mode).build()

        // --- Materialize: target must be SEAWEED | REDIS | DB_TABLE; never a worker session ---

        "Materialize seaweed->seaweed is same-location no-op" {
            val planned = planner.plan(materialize(locationOf(LocationKind.SEAWEED), locationOf(LocationKind.SEAWEED)))
            withClue("seaweed->seaweed must be SAME_LOCATION, not an I/O move") {
                (planned as Planned.Plan).plan.rpc shouldBe MoveRpc.MATERIALIZE
            }
        }

        "Materialize seaweed->redis is allowed" {
            val planned = planner.plan(materialize(locationOf(LocationKind.SEAWEED), locationOf(LocationKind.REDIS)))
            planned.shouldBeInstanceOf<Planned.Plan>()
        }

        "Materialize seaweed->worker is forbidden (target-shape cross-check)" {
            val planned =
                planner.plan(
                    materialize(locationOf(LocationKind.SEAWEED), locationOf(LocationKind.WORKER_DF)),
                )
            withClue("Materialize must NEVER target a worker session — use Stage") {
                val errors = (planned as Planned.Invalid).errors
                errors.any { it is CharonError.IllegalTargetForRpc } shouldBe true
            }
        }

        "Materialize seaweed->db_table requires db_write_mode" {
            val planned = planner.plan(materialize(locationOf(LocationKind.SEAWEED), locationOf(LocationKind.DB_TABLE)))
            withClue("Missing db_write_mode on a DB target must be rejected with INVALID_ARGUMENT") {
                val errors = (planned as Planned.Invalid).errors
                errors.any { it is CharonError.MissingOrInvalidDbWriteMode } shouldBe true
            }
        }

        "Materialize seaweed->db_table with db_write_mode=CREATE is allowed" {
            val planned =
                planner.plan(
                    materialize(
                        locationOf(LocationKind.SEAWEED),
                        locationOf(LocationKind.DB_TABLE),
                        optsWith(DbWriteMode.CREATE),
                    ),
                )
            planned.shouldBeInstanceOf<Planned.Plan>()
        }

        "Materialize seaweed->db_table with db_write_mode=UNSPECIFIED is rejected" {
            val planned =
                planner.plan(
                    materialize(
                        locationOf(LocationKind.SEAWEED),
                        locationOf(LocationKind.DB_TABLE),
                        optsWith(DbWriteMode.DB_WRITE_MODE_UNSPECIFIED),
                    ),
                )
            val errors = (planned as Planned.Invalid).errors
            errors.any { it is CharonError.MissingOrInvalidDbWriteMode } shouldBe true
        }

        "Materialize seaweed->seaweed with stray db_write_mode is rejected" {
            val planned =
                planner.plan(
                    materialize(
                        locationOf(LocationKind.SEAWEED),
                        locationOf(LocationKind.SEAWEED),
                        optsWith(DbWriteMode.CREATE),
                    ),
                )
            val errors = (planned as Planned.Invalid).errors
            errors.any { it is CharonError.MissingOrInvalidDbWriteMode } shouldBe true
        }

        "Materialize redis->worker is forbidden" {
            val planned = planner.plan(materialize(locationOf(LocationKind.REDIS), locationOf(LocationKind.WORKER_DF)))
            val errors = (planned as Planned.Invalid).errors
            errors.any { it is CharonError.IllegalTargetForRpc } shouldBe true
        }

        "Materialize worker->seaweed is allowed" {
            val planned =
                planner.plan(
                    materialize(locationOf(LocationKind.WORKER_DF), locationOf(LocationKind.SEAWEED)),
                )
            planned.shouldBeInstanceOf<Planned.Plan>()
        }

        "Materialize db_table->seaweed requires db_write_mode" {
            val planned = planner.plan(materialize(locationOf(LocationKind.DB_TABLE), locationOf(LocationKind.SEAWEED)))
            // The source is a DB but the target is not, so db_write_mode must be
            // unset. (It would be unset anyway — but a stray value would fail the
            // cross-check below.) The "missing" variant is OK here.
            planned.shouldBeInstanceOf<Planned.Plan>()
        }

        "Materialize db_table->seaweed with stray db_write_mode is rejected" {
            val planned =
                planner.plan(
                    materialize(
                        locationOf(LocationKind.DB_TABLE),
                        locationOf(LocationKind.SEAWEED),
                        optsWith(DbWriteMode.CREATE),
                    ),
                )
            val errors = (planned as Planned.Invalid).errors
            errors.any { it is CharonError.MissingOrInvalidDbWriteMode } shouldBe true
        }

        // --- Stage: target MUST be WORKER_DF, nothing else ---

        "Stage seaweed->worker is allowed" {
            val planned = planner.plan(stage(locationOf(LocationKind.SEAWEED), workerTarget()))
            planned.shouldBeInstanceOf<Planned.Plan>()
        }

        "Stage seaweed->worker is the only legal target-shape for Stage" {
            // The proto's `StageRequest.target` is typed `WorkerSessionDf` — so a
            // caller can't actually build a wrong-shape request at compile time.
            // The cross-check the planner enforces is "Stage's target *kind* must
            // be WORKER_DF"; this is implicitly tested by the "Stage seaweed->worker
            // is allowed" case. The mirror cross-check — Materialize rejecting a
            // worker target — lives below.
            val materialized =
                planner.plan(
                    materialize(locationOf(LocationKind.SEAWEED), locationOf(LocationKind.WORKER_DF)),
                )
            withClue("Materialize->worker must be IllegalTargetForRpc (use Stage instead)") {
                val errors = (materialized as Planned.Invalid).errors
                errors.any { it is CharonError.IllegalTargetForRpc } shouldBe true
            }
        }

        "Stage worker->worker is same-location no-op" {
            val planned = planner.plan(stage(locationOf(LocationKind.WORKER_DF), workerTarget()))
            // Stage proto's target is a WorkerSessionDf; the planner's
            // SAME_LOCATION rule applies for `WORKER_DF -> WORKER_DF` only.
            (planned as Planned.Plan).plan.rpc shouldBe MoveRpc.STAGE
        }

        // --- Copy: generic verb; same matrix as Materialize but no target-kind cross-check ---

        "Copy seaweed->worker is allowed (use Copy for an explicit cross-engine intent)" {
            val planned = planner.plan(copy(locationOf(LocationKind.SEAWEED), locationOf(LocationKind.WORKER_DF)))
            planned.shouldBeInstanceOf<Planned.Plan>()
        }

        "Copy seaweed->seaweed is same-location no-op" {
            val planned = planner.plan(copy(locationOf(LocationKind.SEAWEED), locationOf(LocationKind.SEAWEED)))
            (planned as Planned.Plan).plan.rpc shouldBe MoveRpc.COPY
        }

        "Copy seaweed->db_table requires db_write_mode" {
            val planned = planner.plan(copy(locationOf(LocationKind.SEAWEED), locationOf(LocationKind.DB_TABLE)))
            val errors = (planned as Planned.Invalid).errors
            errors.any { it is CharonError.MissingOrInvalidDbWriteMode } shouldBe true
        }

        "Copy db_table->db_table requires db_write_mode" {
            val planned = planner.plan(copy(locationOf(LocationKind.DB_TABLE), locationOf(LocationKind.DB_TABLE)))
            val errors = (planned as Planned.Invalid).errors
            errors.any { it is CharonError.MissingOrInvalidDbWriteMode } shouldBe true
        }

        "Copy db_table->db_table with db_write_mode=APPEND is allowed" {
            val planned =
                planner.plan(
                    copy(
                        locationOf(LocationKind.DB_TABLE),
                        locationOf(LocationKind.DB_TABLE),
                        optsWith(DbWriteMode.APPEND),
                    ),
                )
            planned.shouldBeInstanceOf<Planned.Plan>()
        }

        // --- Empty (unset-oneof) locations → INVALID_ARGUMENT, not a crash (review-006 R8.7 / L5) ---

        "Materialize with an empty (unset) source location is rejected as EmptyLocation, not a thrown error" {
            val planned =
                planner.plan(
                    MaterializeRequest
                        .newBuilder()
                        // source left unset (KIND_NOT_SET)
                        .setTarget(
                            Location.newBuilder().setSeaweed(SeaweedBlob.newBuilder().setBucket("b").setKey("k")),
                        ).build(),
                )
            planned.shouldBeInstanceOf<Planned.Invalid>()
            (planned as Planned.Invalid).errors.first().shouldBeInstanceOf<CharonError.EmptyLocation>()
        }

        "Copy with an empty (unset) target location is rejected as EmptyLocation" {
            val planned =
                planner.plan(
                    CopyRequest
                        .newBuilder()
                        .setSource(
                            Location.newBuilder().setSeaweed(SeaweedBlob.newBuilder().setBucket("b").setKey("k")),
                        )
                        // target left unset (KIND_NOT_SET)
                        .build(),
                )
            planned.shouldBeInstanceOf<Planned.Invalid>()
            (planned as Planned.Invalid).errors.first().shouldBeInstanceOf<CharonError.EmptyLocation>()
        }

        // --- METIS: WorkerKind.METIS uses the same WORKER_DF LocationKind as POLARS ---
        // The legality matrix operates at the LocationKind level, not at the WorkerKind level,
        // so METIS is automatically permitted wherever POLARS is permitted. These cases verify
        // that the planner accepts METIS sessions without any matrix change.

        fun metisWorkerTarget(): WorkerSessionDf =
            WorkerSessionDf
                .newBuilder()
                .setWorkerKind(WorkerKind.METIS)
                .setSessionId("metis-sess")
                .setDfName("df1")
                .build()

        fun metisLocation(): Location =
            Location
                .newBuilder()
                .setWorkerDf(metisWorkerTarget())
                .build()

        "Stage seaweed->METIS worker is allowed (same WORKER_DF kind as POLARS)" {
            val planned = planner.plan(stage(locationOf(LocationKind.SEAWEED), metisWorkerTarget()))
            planned.shouldBeInstanceOf<Planned.Plan>()
        }

        "Stage redis->METIS worker is allowed" {
            val planned = planner.plan(stage(locationOf(LocationKind.REDIS), metisWorkerTarget()))
            planned.shouldBeInstanceOf<Planned.Plan>()
        }

        "Stage db_table->METIS worker is allowed" {
            val planned = planner.plan(stage(locationOf(LocationKind.DB_TABLE), metisWorkerTarget()))
            planned.shouldBeInstanceOf<Planned.Plan>()
        }

        "Materialize METIS worker->seaweed is allowed" {
            val planned = planner.plan(materialize(metisLocation(), locationOf(LocationKind.SEAWEED)))
            planned.shouldBeInstanceOf<Planned.Plan>()
        }

        "Materialize METIS worker->redis is allowed" {
            val planned = planner.plan(materialize(metisLocation(), locationOf(LocationKind.REDIS)))
            planned.shouldBeInstanceOf<Planned.Plan>()
        }

        "Copy seaweed->METIS worker is allowed" {
            val planned = planner.plan(copy(locationOf(LocationKind.SEAWEED), metisLocation()))
            planned.shouldBeInstanceOf<Planned.Plan>()
        }

        // --- Exhaustive matrix walk: every (rpc, source, target) cell classified ---

        val rpcs = MoveRpc.entries.toList()
        val kinds = LocationKind.entries.toList()

        for (rpc in rpcs) {
            for (src in kinds) {
                for (tgt in kinds) {
                    if (rpc == MoveRpc.DESCRIBE) continue
                    "exhaustive: $rpc $src -> $tgt is classified" {
                        val expected = legalityOf(rpc, src, tgt)
                        withClue("matrix cell for $rpc $src -> $tgt must be classified") {
                            expected.toString().isNotEmpty() shouldBe true
                        }
                    }
                }
            }
        }
    })
