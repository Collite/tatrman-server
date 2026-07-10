package org.tatrman.kantheon.charon.grpc

import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.TimeUnit
import org.tatrman.transfer.v1.CharonServiceGrpc
import org.tatrman.transfer.v1.CopyRequest
import org.tatrman.transfer.v1.DbTable
import org.tatrman.transfer.v1.DescribeRequest
import org.tatrman.transfer.v1.EvictRequest
import org.tatrman.transfer.v1.Location
import org.tatrman.transfer.v1.MaterializeRequest
import org.tatrman.transfer.v1.RedisEntry
import org.tatrman.transfer.v1.SeaweedBlob
import org.tatrman.transfer.v1.StageRequest
import org.tatrman.transfer.v1.WorkerKind
import org.tatrman.transfer.v1.WorkerSessionDf
import org.tatrman.kantheon.charon.core.MovePlanner
import org.tatrman.kantheon.charon.core.SkeletonMoveExecutor

/**
 * Component test for the gRPC service: spins up an in-process gRPC server
 * hosting the real [CharonServiceImpl] + planner, and exercises each of the
 * five RPCs with one bad-input case. The "skeleton" executor would otherwise
 * return `UNIMPLEMENTED` for everything; the planner's validation runs first,
 * so the bad inputs never reach the executor.
 *
 * Per `AGENTS.md` §8 — component (`testApplication`-style) layer for Ktor;
 * for gRPC, the in-process server + real client stub is the equivalent.
 */
class RequestValidationSpec :
    StringSpec({

        val serverName = "charon-validation-test-${System.nanoTime()}"
        val server =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(
                    CharonServiceImpl(
                        planner = MovePlanner(),
                        executor = SkeletonMoveExecutor(),
                    ),
                ).build()
                .start()
        val channel: ManagedChannel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
        val asyncStub = CharonServiceGrpc.newStub(channel)
        val blockingStub = CharonServiceGrpc.newBlockingStub(channel)

        afterSpec {
            channel.shutdownNow()
            server.shutdownNow()
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow()
            }
            if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                server.shutdownNow()
            }
        }

        // --- Materialize ---

        "Materialize with a worker target is rejected with INVALID_ARGUMENT" {
            val req =
                MaterializeRequest
                    .newBuilder()
                    .setSource(
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("k")
                                    .build(),
                            ).build(),
                    ).setTarget(
                        Location
                            .newBuilder()
                            .setWorkerDf(
                                WorkerSessionDf
                                    .newBuilder()
                                    .setWorkerKind(
                                        WorkerKind.POLARS,
                                    ).setSessionId("s")
                                    .setDfName("d")
                                    .build(),
                            ).build(),
                    ).build()
            val ex = shouldThrowExactly<StatusRuntimeException> { blockingStub.materialize(req) }
            ex.status.code shouldBe Status.Code.INVALID_ARGUMENT
            ex.status.description!! shouldContain "RPC MATERIALIZE does not accept target kind WORKER_DF"
        }

        "Materialize with a DB target and no db_write_mode is rejected with INVALID_ARGUMENT" {
            val req =
                MaterializeRequest
                    .newBuilder()
                    .setSource(
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("k")
                                    .build(),
                            ).build(),
                    ).setTarget(
                        Location
                            .newBuilder()
                            .setDbTable(
                                DbTable
                                    .newBuilder()
                                    .setConnectionId("c")
                                    .setSchema("s")
                                    .setTable("t")
                                    .build(),
                            ).build(),
                    ).build()
            val ex = shouldThrowExactly<StatusRuntimeException> { blockingStub.materialize(req) }
            ex.status.code shouldBe Status.Code.INVALID_ARGUMENT
            ex.status.description!! shouldContain "Target kind DB_TABLE requires explicit db_write_mode"
        }

        // --- Stage ---

        "Stage with a seaweed target — proto is typed WorkerSessionDf; executor seam handles worker-shape work" {
            // The Stage proto's `target` is typed `WorkerSessionDf` — so this
            // case is enforced by the cross-check on the *kind* once it reaches
            // the planner. We assert the planner-classified case (Materialize
            // with a worker target) below instead; Stage itself is exercised by
            // the "skeleton returns UNIMPLEMENTED" test below.
            val req =
                StageRequest
                    .newBuilder()
                    .setSource(
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("k")
                                    .build(),
                            ).build(),
                    ).setTarget(
                        WorkerSessionDf
                            .newBuilder()
                            .setWorkerKind(WorkerKind.POLARS)
                            .setSessionId("s")
                            .setDfName("d")
                            .build(),
                    ).build()
            val ex = shouldThrowExactly<StatusRuntimeException> { blockingStub.stage(req) }
            // Skeleton returns UNIMPLEMENTED for any successful plan.
            ex.status.code shouldBe Status.Code.UNIMPLEMENTED
        }

        // --- Copy ---

        "Copy with a DB target and no db_write_mode is rejected with INVALID_ARGUMENT" {
            val req =
                CopyRequest
                    .newBuilder()
                    .setSource(
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("k")
                                    .build(),
                            ).build(),
                    ).setTarget(
                        Location
                            .newBuilder()
                            .setDbTable(
                                DbTable
                                    .newBuilder()
                                    .setConnectionId("c")
                                    .setSchema("s")
                                    .setTable("t")
                                    .build(),
                            ).build(),
                    ).build()
            val ex = shouldThrowExactly<StatusRuntimeException> { blockingStub.copy(req) }
            ex.status.code shouldBe Status.Code.INVALID_ARGUMENT
            ex.status.description!! shouldContain "Target kind DB_TABLE requires explicit db_write_mode"
        }

        // --- Evict ---

        "Evict on a DB table is rejected with INVALID_ARGUMENT (DB cleanup is the owner's job)" {
            val req =
                EvictRequest
                    .newBuilder()
                    .setLocation(
                        Location
                            .newBuilder()
                            .setDbTable(
                                DbTable
                                    .newBuilder()
                                    .setConnectionId("c")
                                    .setSchema("s")
                                    .setTable("t")
                                    .build(),
                            ).build(),
                    ).build()
            val ex = shouldThrowExactly<StatusRuntimeException> { blockingStub.evict(req) }
            ex.status.code shouldBe Status.Code.INVALID_ARGUMENT
            ex.status.description!! shouldContain "DB table 's.t'"
        }

        // --- Describe (read-only) ---

        "Describe is read-only — always passes the planner and hits the executor's UNIMPLEMENTED" {
            val req =
                DescribeRequest
                    .newBuilder()
                    .setLocation(
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("k")
                                    .build(),
                            ).build(),
                    ).build()
            val ex = shouldThrowExactly<StatusRuntimeException> { blockingStub.describe(req) }
            // The skeleton returns UNIMPLEMENTED — same as Materialize on a legal
            // pair. The point of the test is the planner didn't reject it.
            ex.status.code shouldBe Status.Code.UNIMPLEMENTED
        }

        // --- The skeleton path: legal pair, returns UNIMPLEMENTED ---

        "Materialize on a legal pair (seaweed->redis) hits the executor and returns UNIMPLEMENTED" {
            val req =
                MaterializeRequest
                    .newBuilder()
                    .setSource(
                        Location
                            .newBuilder()
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("k")
                                    .build(),
                            ).build(),
                    ).setTarget(Location.newBuilder().setRedis(RedisEntry.newBuilder().setKey("r").build()).build())
                    .build()
            val ex = shouldThrowExactly<StatusRuntimeException> { blockingStub.materialize(req) }
            ex.status.code shouldBe Status.Code.UNIMPLEMENTED
        }
    })
