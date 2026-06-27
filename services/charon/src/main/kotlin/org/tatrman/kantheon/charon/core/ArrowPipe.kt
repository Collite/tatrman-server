package org.tatrman.kantheon.charon.core

import org.apache.arrow.vector.ipc.ArrowReader
import org.tatrman.charon.v1.Location
import org.tatrman.charon.v1.MoveResult

/**
 * The single streaming pump over any Source→Target pair — the heart of the
 * move core (charon/architecture.md §3 last paragraph: "the move core is a
 * single pump (`ArrowPipe`) over any Source→Target pair").
 *
 * Invariants (charon/architecture.md §5, contracts §1):
 *   - **No partial writes** — if the read fails or the cap trips, the
 *     target is never visible. The endpoint contract is "atomic from
 *     the consumer's point of view" (S3 temp-key + rename; Redis SET
 *     single op; DB single transaction; worker stage is session-scoped
 *     and re-runnable). `ArrowPipe` itself doesn't do the atomicity —
 *     it delegates to the [Target] implementation, which must use the
 *     appropriate strategy.
 *   - **Schema fingerprint verified** end-to-end when
 *     `MoveOptions.expected_schema_fingerprint` is supplied, *before* any
 *     bytes flow. A mismatch → `CharonError.FingerprintMismatch`, no
 *     write attempted.
 *   - **Bounded memory** — chunks of `options.chunk_rows` rows
 *     (default 65536, contracts §6).
 *   - **Byte cap** — `options.max_bytes` (or server default) is checked
 *     per-batch; exceeding it → `CharonError.ByteCapExceeded`, target
 *     is unwound (the endpoint's `discard()` hook).
 */
object ArrowPipe {
    /**
     * Pump bytes from [source] to [target]. Returns the assembled [MoveResult]
     * on success; `Either.Left(CharonError)` on any failure (typed per
     * contracts §1 error model).
     */
    fun pipe(
        rpc: MoveRpc,
        source: Source,
        target: Target,
        sourceLocation: Location,
        targetLocation: Location,
        options: PipeOptions,
    ): Either<CharonError, MoveResult> {
        val started = System.currentTimeMillis()

        // 1. Open the source — fail-fast on a missing key.
        val stream =
            source.open() ?: return Either.Left(
                CharonError.SourceNotFound(source.kind(), source.ref()),
            )

        stream.use { src ->
            // 2. Pre-write integrity check: schema fingerprint.
            val actualFp = Integrity.fingerprint(src.vectorSchemaRoot.schema)
            if (options.expectedSchemaFingerprint != null &&
                options.expectedSchemaFingerprint != actualFp
            ) {
                return Either.Left(
                    CharonError.FingerprintMismatch(
                        expected = options.expectedSchemaFingerprint,
                        actual = actualFp,
                    ),
                )
            }

            // 3. Open the target — gives the endpoint a chance to set up
            //    its atomic-write strategy (e.g. S3 temp-key). If it fails,
            //    the source stream is closed by the `use` above.
            val writeReceipt =
                try {
                    target.begin(src.vectorSchemaRoot.schema)
                } catch (e: UnmappableTypeException) {
                    // A DB target column with no §5 mapping — FAILED_PRECONDITION,
                    // not UNAVAILABLE. Let the executor map the typed exception.
                    throw e
                } catch (e: DbWritePreconditionException) {
                    // CREATE-on-existing / APPEND-incompatible — FAILED_PRECONDITION.
                    throw e
                } catch (e: Exception) {
                    return Either.Left(
                        CharonError.EndpointUnavailable(target.ref() + ": " + e.message),
                    )
                }

            // 4. Pump chunks. Byte cap is checked per-batch; total is the
            //    running sum so a single batch can't blow past the cap.
            //    We use ArrowReader.bytesRead (cumulative) and snapshot
            //    per iteration to get the per-batch delta.
            var rowCount = 0L
            var byteCount = 0L
            var previousBytes = src.bytesRead()
            try {
                while (src.loadNextBatch()) {
                    val currentBytes = src.bytesRead()
                    val batchBytes = (currentBytes - previousBytes).coerceAtLeast(0L)
                    if (byteCount + batchBytes > options.maxBytes) {
                        // Don't commit the partial write.
                        target.discard(writeReceipt)
                        return Either.Left(
                            CharonError.ByteCapExceeded(
                                bytes = byteCount + batchBytes,
                                cap = options.maxBytes,
                            ),
                        )
                    }
                    target.writeBatch(writeReceipt, src.vectorSchemaRoot)
                    rowCount += src.vectorSchemaRoot.rowCount.toLong()
                    byteCount += batchBytes
                    previousBytes = currentBytes
                }
                // `commit()` stays inside this try on purpose: endpoints rely on
                // `discard()` to clean up after a *failed* commit (e.g. Seaweed's
                // commit writes a temp key then copy+delete — only `discard`
                // removes the temp key if the copy fails). Endpoint `discard` is
                // idempotent w.r.t. the cleanup `commit` already did in its own
                // `finally`, so a commit-failure → discard is safe, not a
                // double-free (review finding, 2026-06-26).
                val commitReceipt = target.commit(writeReceipt)
                val result =
                    MoveResult
                        .newBuilder()
                        .setTarget(targetLocation)
                        .setSchemaFingerprint(actualFp)
                        .setSchemaJson(src.vectorSchemaRoot.schema.toString())
                        .setRowCount(rowCount)
                        .setSizeBytes(byteCount)
                        .setDurationMs(System.currentTimeMillis() - started)
                        .build()

                // `commitReceipt` is the endpoint-specific return from
                // `commit()` (e.g. the real S3 key). It's reserved for
                // future per-endpoint metrics (move bytes via the receipt
                // header, etc.) — not consumed at Stage 1.2.
                @Suppress("UNUSED_VARIABLE")
                val unusedReceipt = commitReceipt
                return Either.Right(result)
            } catch (e: WorkerOpUnsupportedException) {
                // A worker engine with no ingest path (POLARS stage-in) —
                // unwind and let the executor map it to UNIMPLEMENTED.
                target.discard(writeReceipt)
                throw e
            } catch (e: WorkerDfNotFoundException) {
                target.discard(writeReceipt)
                throw e
            } catch (e: io.grpc.StatusException) {
                // A worker fault on stage-in (e.g. workspace_cap_exceeded →
                // RESOURCE_EXHAUSTED) — unwind, then let the executor map the
                // status to the right typed error rather than UNAVAILABLE.
                target.discard(writeReceipt)
                throw e
            } catch (e: io.grpc.StatusRuntimeException) {
                target.discard(writeReceipt)
                throw e
            } catch (e: Exception) {
                // 5. Mid-stream / commit failure — unwind the target (no visible
                //    partial object). The source stream is closed by
                //    `use` above.
                target.discard(writeReceipt)
                return Either.Left(
                    CharonError.EndpointUnavailable(
                        target.ref() + " mid-stream: " + (e.message ?: e::class.simpleName.orEmpty()),
                    ),
                )
            }
        }
    }
}

/** What `ArrowPipe` needs from a [Source] — a thin alias for the IPC reader
 *  so the pipe hot path is testable with a synthetic in-memory stream. */
interface Source {
    /** Open the source stream. The returned reader is closed by the pipe
     *  via `use {}`; returns null if the source is absent (e.g. S3 NoSuchKey).
     *
     *  Returns the base [ArrowReader] so a source can stream lazily — the
     *  blob endpoints return an `ArrowStreamReader` (IPC bytes), while the DB
     *  endpoint returns a `JdbcArrowReader` that pulls row chunks from a live
     *  `ResultSet` (bounded memory, no full materialisation). Both satisfy the
     *  pipe's `vectorSchemaRoot` / `loadNextBatch` / `bytesRead` contract. */
    fun open(): ArrowReader?

    /** The `LocationKind` for error messages + metrics labels. */
    fun kind(): LocationKind

    /** A short reference (e.g. `bucket/key`) for error messages. */
    fun ref(): String
}

/** What `ArrowPipe` needs from a [Target] — the three-phase write
 *  (`begin` → `writeBatch` → `commit`/`discard`) gives the endpoint the
 *  atomicity hook it needs (S3 temp-key, etc.). */
interface Target {
    /** Set up the write — return a receipt the pipe passes to the
     *  subsequent calls. The endpoint opens the temp key / starts the
     *  transaction here. */
    fun begin(schema: org.apache.arrow.vector.types.pojo.Schema): Any

    /** Write a single batch (the schema was set in `begin`). The pipe
     *  loops this for every `chunk_rows` rows. */
    fun writeBatch(
        receipt: Any,
        root: org.apache.arrow.vector.VectorSchemaRoot,
    )

    /** Commit the write — finalise the temp-key + rename, COMMIT the
     *  transaction, etc. Returns a receipt the executor can use to
     *  populate `MoveResult.size_bytes` (already computed by the pipe)
     *  or to log. */
    fun commit(receipt: Any): Any

    /** Unwind the write — the pipe calls this on a mid-stream failure
     *  (cap, exception, source-absent). The endpoint deletes the temp
     *  key / ROLLBACKs the transaction. */
    fun discard(receipt: Any)

    /** The `LocationKind` for error messages + metrics labels. */
    fun kind(): LocationKind

    /** A short reference for error messages. */
    fun ref(): String
}

/** Pipe options — distilled from the proto `MoveOptions` for the pipe's
 *  use; the proto field is the wire surface, this is the in-process view. */
data class PipeOptions(
    /** Default 65536, contracts §6. */
    val chunkRows: Int = 65536,
    /** Default 128 MiB (charon/contracts.md §8). */
    val maxBytes: Long = 128L * 1024L * 1024L,
    /** When non-null, verified against the source's actual fingerprint
     *  before any bytes flow. */
    val expectedSchemaFingerprint: String? = null,
)
