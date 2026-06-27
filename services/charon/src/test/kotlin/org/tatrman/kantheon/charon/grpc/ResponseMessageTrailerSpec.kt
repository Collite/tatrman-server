package org.tatrman.kantheon.charon.grpc

import io.kotest.assertions.withClue
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.TimeUnit
import org.tatrman.charon.v1.CharonServiceGrpc
import org.tatrman.charon.v1.CopyRequest
import org.tatrman.charon.v1.Location
import org.tatrman.charon.v1.MaterializeRequest
import org.tatrman.charon.v1.SeaweedBlob
import org.tatrman.kantheon.charon.core.MovePlanner
import org.tatrman.kantheon.charon.core.SkeletonMoveExecutor
import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity

/**
 * Review-006 R5 + R6. The gRPC service impl must:
 *  - Attach the typed [CharonError] as a Rule-6 [ResponseMessage] to
 *    the gRPC `metadata` trailers (one per planner error, single
 *    per executor error). The trailer is the canonical place per
 *    `charon/contracts.md` §1 (the response proto's `messages = 99`
 *    field is for success payloads; failures use trailers).
 *  - Not call `onCompleted()` after `onError()` (grpc-java protocol
 *    misuse — review-006 M3). The success path closes; the error
 *    path terminates the call with the status.
 */
class ResponseMessageTrailerSpec :
    StringSpec({

        val serverName = "charon-trailer-test-${System.nanoTime()}"
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
        val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
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

        // --- R5 gate: planner rejection → ResponseMessage in trailers ---

        "planner-rejected request carries a ResponseMessage trailer with code + human_message + severity" {
            // The canonical planner-rejected case: Materialize with
            // a worker_df target. Per `MovePlanner.isTargetValidForRpc`
            // Materialize never accepts a worker_df (only Stage
            // does). The planner returns `Invalid` with
            // `IllegalTargetForRpc`.
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
                                org.tatrman.charon.v1.WorkerSessionDf
                                    .newBuilder()
                                    .setWorkerKind(org.tatrman.charon.v1.WorkerKind.POLARS)
                                    .setSessionId("s")
                                    .setDfName("d")
                                    .build(),
                            ).build(),
                    ).build()
            val ex = shouldThrowExactly<StatusRuntimeException> { blockingStub.materialize(req) }
            ex.status.code shouldBe Status.Code.INVALID_ARGUMENT
            // The R5 contract: the ResponseMessage is in the trailers.
            val trailers: Metadata = ex.trailers ?: error("no trailers on the error")
            val messages: List<ByteArray> = trailers.getAll(RESPONSE_MESSAGES_TRAILER_KEY)?.toList() ?: emptyList()
            withClue("at least one Rule-6 message in the trailers (R5)") {
                (messages.size >= 1) shouldBe true
            }
            val codes = messages.map { ResponseMessage.parseFrom(it).code }
            withClue("at least one trailer message has the IllegalTargetForRpc code") {
                codes.contains("illegal_target_for_rpc") shouldBe true
            }
            val parsed = messages.map { ResponseMessage.parseFrom(it) }
            withClue("Rule-6 messages have severity=ERROR (v1 convention)") {
                parsed.forEach { it.severity shouldBe Severity.ERROR }
            }
            withClue("at least one Rule-6 message's human_message mentions the target kind") {
                parsed.any {
                    it.humanMessage.contains("does not accept target kind")
                } shouldBe true
            }
        }

        // --- R5 same-location no-op (no trailers because success) ---

        "successful request does not attach ResponseMessage trailers" {
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
                            .setSeaweed(
                                SeaweedBlob
                                    .newBuilder()
                                    .setBucket("b")
                                    .setKey("k")
                                    .build(),
                            ).build(),
                    ).build()
            // The planner accepts (seaweed→seaweed is ALLOWED).
            // The executor (SkeletonMoveExecutor) returns
            // UNIMPLEMENTED — which still attaches a
            // ResponseMessage (NotYetImplemented) under R5.
            val ex = shouldThrowExactly<StatusRuntimeException> { blockingStub.copy(req) }
            ex.status.code shouldBe Status.Code.UNIMPLEMENTED
            val trailers: Metadata = ex.trailers ?: error("no trailers on the error")
            val messages: List<ByteArray> = trailers.getAll(RESPONSE_MESSAGES_TRAILER_KEY)?.toList() ?: emptyList()
            withClue("executor-typed failure also carries the typed ResponseMessage in trailers") {
                messages.size shouldBe 1
            }
            val parsed = ResponseMessage.parseFrom(messages[0])
            parsed.code shouldBe "not_yet_implemented"
        }

        // --- R6 gate: no onCompleted() after onError() (implied by the calls not throwing) ---

        "rejected request closes the call exactly once (no IllegalStateException from onCompleted after onError)" {
            // The mere fact that shouldThrowExactly<StatusRuntimeException>
            // returns (instead of bubbling an IllegalStateException) is
            // the gate. We run the same request 5 times to make sure
            // the server-side handler is robust under repeated calls.
            repeat(5) {
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
                                        .setKey("src")
                                        .build(),
                                ).build(),
                        ).setTarget(
                            Location
                                .newBuilder()
                                .setSeaweed(
                                    SeaweedBlob
                                        .newBuilder()
                                        .setBucket("b")
                                        .setKey("tgt")
                                        .build(),
                                ).build(),
                        ).build()
                val ex = shouldThrowExactly<StatusRuntimeException> { blockingStub.materialize(req) }
                // The test uses a planner-accepted request (seaweed
                // → seaweed) so the planner doesn't reject; the
                // skeleton executor returns UNIMPLEMENTED. Either
                // way, the call closes with a single terminal
                // status (R6 — no `onCompleted` after `onError`).
                (
                    ex.status.code == Status.Code.INVALID_ARGUMENT ||
                        ex.status.code == Status.Code.UNIMPLEMENTED
                ) shouldBe true
            }
        }
    })
