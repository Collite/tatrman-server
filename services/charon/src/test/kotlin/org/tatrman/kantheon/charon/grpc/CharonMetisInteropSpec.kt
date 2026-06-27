package org.tatrman.kantheon.charon.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.charon.v1.Location
import org.tatrman.charon.v1.SeaweedBlob
import org.tatrman.charon.v1.StageRequest
import org.tatrman.charon.v1.WorkerKind
import org.tatrman.charon.v1.WorkerSessionDf
import org.tatrman.kantheon.charon.core.MovePlanner
import org.tatrman.kantheon.charon.core.Planned

/**
 * Phase 3 Stage 3.4 — Charon ↔ Metis interop: legality gate.
 *
 * Verifies that [MovePlanner] accepts [WorkerKind.METIS] sessions wherever
 * [WorkerKind.POLARS] sessions are accepted. The legality matrix operates at
 * the [LocationKind.WORKER_DF] level, not at the [WorkerKind] level, so METIS
 * is covered without any matrix change.
 *
 * The full live round-trip (Charon executor calling the Metis gRPC service) is
 * an integration test (out of scope for Stage 3.4). This spec focuses purely on
 * the planning / validation gate.
 */
class CharonMetisInteropSpec :
    StringSpec({

        val planner = MovePlanner()

        fun seaweedSource(): Location =
            Location
                .newBuilder()
                .setSeaweed(
                    SeaweedBlob
                        .newBuilder()
                        .setBucket("results")
                        .setKey("session/df1.arrow")
                        .build(),
                ).build()

        fun metisTarget(): WorkerSessionDf =
            WorkerSessionDf
                .newBuilder()
                .setWorkerKind(WorkerKind.METIS)
                .setSessionId("metis-session-1")
                .setDfName("input_series")
                .build()

        fun stageRequest(
            source: Location,
            target: WorkerSessionDf,
        ): StageRequest =
            StageRequest
                .newBuilder()
                .setSource(source)
                .setTarget(target)
                .build()

        "Stage seaweed->METIS worker: plan is accepted by the legality gate" {
            val planned = planner.plan(stageRequest(seaweedSource(), metisTarget()))
            // Verify the plan is valid (legality gate passes for METIS just as for POLARS)
            planned.shouldBeInstanceOf<Planned.Plan>()
        }

        "Stage seaweed->METIS worker: plan carries correct RPC" {
            val planned = planner.plan(stageRequest(seaweedSource(), metisTarget()))
            val plan = (planned as Planned.Plan).plan
            plan.rpc shouldBe org.tatrman.kantheon.charon.core.MoveRpc.STAGE
        }

        "Stage seaweed->METIS worker: source location is preserved in the plan" {
            val src = seaweedSource()
            val planned = planner.plan(stageRequest(src, metisTarget()))
            val plan = (planned as Planned.Plan).plan
            plan.source.seaweed.bucket shouldBe "results"
            plan.source.seaweed.key shouldBe "session/df1.arrow"
        }

        "Stage seaweed->METIS worker: target WorkerKind is preserved in the plan" {
            val planned = planner.plan(stageRequest(seaweedSource(), metisTarget()))
            val plan = (planned as Planned.Plan).plan
            // The target is promoted to a Location wrapping the WorkerSessionDf
            plan.target!!.workerDf.workerKind shouldBe WorkerKind.METIS
            plan.target!!.workerDf.sessionId shouldBe "metis-session-1"
            plan.target!!.workerDf.dfName shouldBe "input_series"
        }

        "Stage METIS worker->METIS worker is same-location (no-op plan, not an error)" {
            val metisLoc =
                Location
                    .newBuilder()
                    .setWorkerDf(metisTarget())
                    .build()
            val planned =
                planner.plan(
                    StageRequest
                        .newBuilder()
                        .setSource(metisLoc)
                        .setTarget(metisTarget())
                        .build(),
                )
            // SAME_LOCATION is still a valid Plan (the planner returns Plan, not Invalid)
            planned.shouldBeInstanceOf<Planned.Plan>()
            (planned as Planned.Plan).plan.rpc shouldBe org.tatrman.kantheon.charon.core.MoveRpc.STAGE
        }
    })
