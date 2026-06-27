package org.tatrman.kantheon.charon.grpc

import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import org.tatrman.charon.v1.CharonServiceGrpc
import org.tatrman.charon.v1.CopyRequest
import org.tatrman.charon.v1.DescribeRequest
import org.tatrman.charon.v1.DescribeResult
import org.tatrman.charon.v1.EvictRequest
import org.tatrman.charon.v1.EvictResult
import org.tatrman.charon.v1.MaterializeRequest
import org.tatrman.charon.v1.MoveResult
import org.tatrman.charon.v1.StageRequest
import org.tatrman.kantheon.charon.core.CharonError
import org.tatrman.kantheon.charon.core.Either
import org.tatrman.kantheon.charon.core.MoveExecutor
import org.tatrman.kantheon.charon.core.MovePlanner
import org.tatrman.kantheon.charon.core.Planned
import org.tatrman.kantheon.charon.core.toResponseMessage
import org.tatrman.kantheon.charon.core.toStatus
import org.slf4j.LoggerFactory

/**
 * The gRPC service implementation behind the five Charon RPCs. At Stage 1.1
 * the body is intentionally thin: the planner does the validation (the
 * "interesting" contract work); the executor seam returns `UNIMPLEMENTED`.
 *
 * Error surfacing follows `charon/contracts.md` §1: every typed failure maps to
 * the right gRPC `Status.Code` via [CharonError.toStatus]; the Rule-6
 * `ResponseMessage` rides on the gRPC `metadata` trailers (via a `Metadata`
 * key holding the serialised `ResponseMessage` proto bytes) so the caller
 * can read the structured error payload alongside the bare status
 * description. Review-006 M2 fixed the missing trailer attach — the
 * `toResponseMessage()` helpers in `Errors.kt` were always present but
 * never plumbed into the gRPC layer.
 *
 * The compile gate for Stage 1.1: a `RequestValidationSpec` covers all five
 * RPCs with one bad-input case each — they all return the right
 * `INVALID_ARGUMENT` (or `FAILED_PRECONDITION` for fingerprint mismatch, etc.).
 */
class CharonServiceImpl(
    private val planner: MovePlanner = MovePlanner(),
    private val executor: MoveExecutor,
) : CharonServiceGrpc.CharonServiceImplBase() {
    private val log = LoggerFactory.getLogger(CharonServiceImpl::class.java)

    override fun materialize(
        request: MaterializeRequest,
        responseObserver: StreamObserver<MoveResult>,
    ) {
        handle(
            rpcName = "Materialize",
            planned = planner.plan(request),
            responseObserver = responseObserver,
            execute = executor::materialize,
        )
    }

    override fun stage(
        request: StageRequest,
        responseObserver: StreamObserver<MoveResult>,
    ) {
        handle(
            rpcName = "Stage",
            planned = planner.plan(request),
            responseObserver = responseObserver,
            execute = executor::stage,
        )
    }

    override fun copy(
        request: CopyRequest,
        responseObserver: StreamObserver<MoveResult>,
    ) {
        handle(
            rpcName = "Copy",
            planned = planner.plan(request),
            responseObserver = responseObserver,
            execute = executor::copy,
        )
    }

    override fun evict(
        request: EvictRequest,
        responseObserver: StreamObserver<EvictResult>,
    ) {
        when (val planned = planner.plan(request)) {
            is Planned.Invalid -> {
                val status =
                    planned.errors
                        .first()
                        .toStatus()
                        .withDescription(planned.errors.joinToString("; ") { it.humanMessage })
                responseObserver.onError(attachRule6Trailer(status, planned.errors))
                return
            }
            is Planned.Plan ->
                when (val result = executor.evict(planned.plan)) {
                    is Either.Left -> {
                        responseObserver.onError(attachRule6Trailer(result.value))
                        return
                    }
                    is Either.Right -> {
                        responseObserver.onNext(result.value)
                        responseObserver.onCompleted()
                    }
                }
        }
    }

    override fun describe(
        request: DescribeRequest,
        responseObserver: StreamObserver<DescribeResult>,
    ) {
        when (val planned = planner.plan(request)) {
            is Planned.Invalid -> {
                // Describe's planner never returns `Invalid` in v1 (Describe is
                // read-only; legality is always ALLOWED). Defensive branch.
                val status =
                    planned.errors
                        .first()
                        .toStatus()
                        .withDescription(planned.errors.joinToString("; ") { it.humanMessage })
                responseObserver.onError(attachRule6Trailer(status, planned.errors))
                return
            }
            is Planned.Plan ->
                when (val result = executor.describe(planned.plan)) {
                    is Either.Left -> {
                        responseObserver.onError(attachRule6Trailer(result.value))
                        return
                    }
                    is Either.Right -> {
                        responseObserver.onNext(result.value)
                        responseObserver.onCompleted()
                    }
                }
        }
    }

    /**
     * The shared 3-arg-MoveResult body. Failure modes:
     *  - planner said `Invalid` → first error's status (`INVALID_ARGUMENT` or
     *    `FAILED_PRECONDITION`); the `ResponseMessage`(s) ride on the gRPC
     *    trailers under [RESPONSE_MESSAGES_TRAILER_KEY] (review-006 M2).
     *  - planner said `Plan` → executor's `Either.Left` becomes the
     *    `CharonError.toStatus()`; the `ResponseMessage` is attached as a
     *    single-message trailer.
     *
     * **No `onCompleted()` after `onError()`** (review-006 M3). The
     * error path `return`s immediately; the success path completes.
     */
    private fun handle(
        rpcName: String,
        planned: Planned,
        responseObserver: StreamObserver<MoveResult>,
        execute: (org.tatrman.kantheon.charon.core.Plan) -> Either<CharonError, MoveResult>,
    ) {
        when (planned) {
            is Planned.Invalid -> {
                val first = planned.errors.first()
                val status =
                    first
                        .toStatus()
                        .withDescription(planned.errors.joinToString("; ") { it.humanMessage })
                log.debug("{}: planner rejected request: {}", rpcName, planned.errors)
                responseObserver.onError(attachRule6Trailer(status, planned.errors))
            }
            is Planned.Plan ->
                when (val result = execute(planned.plan)) {
                    is Either.Left -> {
                        log.debug("{}: executor returned {}: {}", rpcName, result.value.code, result.value.humanMessage)
                        responseObserver.onError(attachRule6Trailer(result.value))
                    }
                    is Either.Right -> {
                        responseObserver.onNext(result.value)
                        responseObserver.onCompleted()
                    }
                }
        }
    }
}

/**
 * The Rule-6 trailer key. We serialise the `ResponseMessage` proto as
 * bytes (one message per value); the caller side reads with
 * [RESPONSE_MESSAGES_TRAILER_KEY] + `MessageParser<org.tatrman.kantheon.common.v1.ResponseMessage>`.
 */
val RESPONSE_MESSAGES_TRAILER_KEY: Metadata.Key<ByteArray> =
    Metadata.Key.of(
        "charon-response-messages-bin",
        Metadata.BINARY_BYTE_MARSHALLER,
    )

/** Build a [Metadata] with one [RESPONSE_MESSAGES_TRAILER_KEY] entry per
 *  [error] (Rule-6 `ResponseMessage` serialised to bytes). */
private fun rule6TrailersFor(errors: List<CharonError>): Metadata =
    Metadata().apply {
        errors.forEach { err ->
            put(RESPONSE_MESSAGES_TRAILER_KEY, err.toResponseMessage().toByteArray())
        }
    }

/** Attach a single [error]'s `ResponseMessage` to the gRPC trailers. */
private fun attachRule6Trailer(error: CharonError): StatusException =
    error
        .toStatus()
        .withDescription(error.humanMessage)
        .asException(rule6TrailersFor(listOf(error)))

/** Attach one `ResponseMessage` per planner error to a status's trailers. */
private fun attachRule6Trailer(
    status: Status,
    errors: List<CharonError>,
): StatusException = status.asException(rule6TrailersFor(errors))

/** Convenience constructor for tests + the bootstrap: pair the planner with
 *  the Stage 1.1 skeleton executor (returns `UNIMPLEMENTED` for every RPC). */
fun CharonServiceImpl.skeleton(): CharonServiceImpl =
    CharonServiceImpl(
        planner = MovePlanner(),
        executor =
            org.tatrman.kantheon.charon.core
                .SkeletonMoveExecutor(),
    )
