package org.tatrman.kantheon.charon.core

import org.tatrman.transfer.v1.DescribeResult
import org.tatrman.transfer.v1.EvictResult
import org.tatrman.transfer.v1.MoveResult

/**
 * The I/O seam Charon's gRPC layer calls. The real implementations
 * (SeaweedEndpoint, RedisEndpoint, DbTable via AdbcReader/Writer,
 * WorkerSessionDf via WorkerEndpoint) plug in over Stages 1.2 / 1.3 / 2.1 / 3.1.
 *
 * At Stage 1.1 every method returns [CharonError.NotYetImplemented] — the
 * RPC layer wraps that into a gRPC `UNIMPLEMENTED` status. The contract being
 * tested is "the planner is right; the executor is the next layer to fill."
 */
interface MoveExecutor {
    fun materialize(plan: Plan): Either<CharonError, MoveResult>

    fun stage(plan: Plan): Either<CharonError, MoveResult>

    fun copy(plan: Plan): Either<CharonError, MoveResult>

    fun evict(plan: Plan): Either<CharonError, EvictResult>

    fun describe(plan: Plan): Either<CharonError, DescribeResult>
}

/** A two-sided union — `Right` carries the success, `Left` carries the typed
 *  failure. Naming follows the canonical functional spelling so readers
 *  familiar with `Either` (Scala, Haskell, Arrow for Kotlin) recognise it
 *  without explanation. */
sealed class Either<out L, out R> {
    data class Left<L>(
        val value: L,
    ) : Either<L, Nothing>()

    data class Right<R>(
        val value: R,
    ) : Either<Nothing, R>()
}

/** The Stage 1.1 skeleton executor — every method returns `UNIMPLEMENTED`. */
class SkeletonMoveExecutor : MoveExecutor {
    override fun materialize(plan: Plan): Either<CharonError, MoveResult> =
        Either.Left(CharonError.NotYetImplemented(MoveRpc.MATERIALIZE))

    override fun stage(plan: Plan): Either<CharonError, MoveResult> =
        Either.Left(CharonError.NotYetImplemented(MoveRpc.STAGE))

    override fun copy(plan: Plan): Either<CharonError, MoveResult> =
        Either.Left(CharonError.NotYetImplemented(MoveRpc.COPY))

    override fun evict(plan: Plan): Either<CharonError, EvictResult> =
        Either.Left(CharonError.NotYetImplemented(MoveRpc.EVICT))

    override fun describe(plan: Plan): Either<CharonError, DescribeResult> =
        Either.Left(CharonError.NotYetImplemented(MoveRpc.DESCRIBE))
}
