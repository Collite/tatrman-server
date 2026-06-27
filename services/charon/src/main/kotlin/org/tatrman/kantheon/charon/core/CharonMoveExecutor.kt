package org.tatrman.kantheon.charon.core

import io.lettuce.core.api.StatefulRedisConnection
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.sql.SQLException
import org.slf4j.LoggerFactory
import org.tatrman.charon.v1.DbTable
import org.tatrman.charon.v1.DescribeResult
import org.tatrman.charon.v1.EvictResult
import org.tatrman.charon.v1.Location
import org.tatrman.charon.v1.MoveResult
import org.tatrman.kantheon.charon.endpoints.JdbcAdbcReader
import org.tatrman.kantheon.charon.endpoints.JdbcAdbcWriter
import org.tatrman.kantheon.charon.endpoints.LettuceRedisOps
import org.tatrman.kantheon.charon.endpoints.RedisEndpoint
import org.tatrman.kantheon.charon.endpoints.RedisOps
import org.tatrman.kantheon.charon.endpoints.SeaweedEndpoint
import org.tatrman.kantheon.charon.endpoints.WorkerEndpoint
import software.amazon.awssdk.services.s3.S3Client

private val log = LoggerFactory.getLogger(CharonMoveExecutor::class.java)

/**
 * The Stage 1.3 real executor — Seaweed (Stage 1.2) + Redis (Stage 1.3)
 * wired into the planner's `Either.Left/Right` shape.
 *
 * **Atomicity.** `ArrowPipe` is the move core and enforces no-partial-write
 * via the Source/Target contract (charon/architecture.md §5). For S3
 * targets [SeaweedEndpoint] does temp-key + copy + delete; for Redis targets
 * [RedisEndpoint] does a single SET with EX (TTL) — atomic at the server.
 * The schema fingerprint rides alongside the Redis value as a sidecar key
 * `[key]:schema-fp` with the same TTL.
 *
 * **What's still stubbed.** DB-target / DB-source (Phase 2 / Stage 2.1 +
 * Stage 2.2), Worker-endpoint (Stage 3.1). These return
 * [CharonError.NotYetImplemented] and surface as `UNIMPLEMENTED` on the
 * gRPC wire — same contract as the Stage 1.1 skeleton.
 *
 * **Metric labels** are service-scoped per `AGENTS.md` §7
 * (`charon_moves_total`, `charon_move_duration_ms`).
 */
class CharonMoveExecutor(
    private val s3Client: S3Client,
    private val redisOps: RedisOps,
    private val redisMaxValueBytes: Long = 64L * 1024L * 1024L,
    private val registry: MeterRegistry = io.micrometer.core.instrument.Metrics.globalRegistry,
    private val redisDefaultTtlSeconds: Long? = 86_400L,
    /** The named-connection registry (Phase 2). `null` ⇒ no DB edges (blob-only
     *  pod); any `db_table` request then resolves to `UnknownConnectionId`. */
    private val connectionRegistry: ConnectionRegistry? = null,
    /** The JDBC connection provider (Phase 2). `null` ⇒ DB edges unavailable. */
    private val dbProvider: DbConnectionProvider? = null,
    /** The worker-gateway factory (Phase 3). `null` ⇒ no worker edges; any
     *  `worker_df` request resolves to `WorkerEngineUnavailable`. */
    private val workerGatewayFactory: WorkerGatewayFactory? = null,
) : MoveExecutor {
    /** Test wiring — takes a [RedisOps] mock directly. */
    constructor(
        s3Client: S3Client,
        redisOps: RedisOps,
    ) : this(s3Client, redisOps, 64L * 1024L * 1024L)

    /** Production wiring — wraps a Lettuce connection in [LettuceRedisOps]. */
    constructor(
        s3Client: S3Client,
        redisConnection: StatefulRedisConnection<ByteArray, ByteArray>,
        redisMaxValueBytes: Long = 64L * 1024L * 1024L,
        registry: MeterRegistry = io.micrometer.core.instrument.Metrics.globalRegistry,
        redisDefaultTtlSeconds: Long? = 86_400L,
        connectionRegistry: ConnectionRegistry? = null,
        dbProvider: DbConnectionProvider? = null,
        workerGatewayFactory: WorkerGatewayFactory? = null,
    ) : this(
        s3Client,
        LettuceRedisOps(redisConnection),
        redisMaxValueBytes,
        registry,
        redisDefaultTtlSeconds,
        connectionRegistry,
        dbProvider,
        workerGatewayFactory,
    )

    // Single parent allocator shared by every per-move endpoint
    // (source and target). Arrow's `TransferPair` requires
    // source and destination vectors to share a root allocator;
    // a fresh `RootAllocator()` per endpoint instance would
    // trip the assertion. The parent outlives every move
    // (children are closed by the endpoints at commit/discard).
    private val parentAllocator: org.apache.arrow.memory.RootAllocator =
        org.apache.arrow.memory
            .RootAllocator()

    private val redis = RedisEndpoint(redisOps, redisMaxValueBytes, redisDefaultTtlSeconds, parentAllocator)

    private val dbReader: JdbcAdbcReader? = dbProvider?.let { JdbcAdbcReader(it, parentAllocator) }
    private val dbWriter: JdbcAdbcWriter? = dbProvider?.let { JdbcAdbcWriter(it) }

    override fun materialize(plan: Plan): Either<CharonError, MoveResult> = copyOrMaterialize(plan)

    override fun copy(plan: Plan): Either<CharonError, MoveResult> = copyOrMaterialize(plan)

    override fun stage(plan: Plan): Either<CharonError, MoveResult> {
        // Stage moves any source INTO a worker session DF (target = worker_df,
        // enforced by the planner). Same generic path as a worker-target Copy.
        val tgt = plan.target ?: return Either.Left(CharonError.IllegalTargetForRpc(plan.rpc, plan.source.kind()))
        val started = System.nanoTime()
        val result = genericMove(plan, plan.source, tgt)
        recordMetrics(plan.rpc, plan.source.kind(), tgt.kind(), resultLabel(result), started)
        return result
    }

    override fun evict(plan: Plan): Either<CharonError, EvictResult> {
        val loc = plan.source
        return when {
            loc.hasSeaweed() -> Either.Right(SeaweedEndpoint(s3Client, parentAllocator).evict(loc.seaweed))
            loc.hasRedis() -> Either.Right(redis.evict(loc.redis))
            loc.hasWorkerDf() ->
                when (val g = workerGatewayFor(loc)) {
                    is Either.Left -> g
                    is Either.Right ->
                        try {
                            Either.Right(g.value.evict(loc.workerDf.sessionId, loc.workerDf.dfName))
                        } catch (e: WorkerOpUnsupportedException) {
                            Either.Left(CharonError.WorkerOpUnsupported(e.engine.name, e.op, e.detail))
                        }
                }
            else -> Either.Left(CharonError.NotYetImplemented(plan.rpc))
        }
    }

    override fun describe(plan: Plan): Either<CharonError, DescribeResult> {
        val loc = plan.source
        return when {
            loc.hasSeaweed() -> Either.Right(SeaweedEndpoint(s3Client, parentAllocator).describe(loc.seaweed))
            loc.hasRedis() -> Either.Right(redis.describe(loc.redis))
            loc.hasDbTable() -> describeDbTable(loc.dbTable)
            loc.hasWorkerDf() ->
                when (val g = workerGatewayFor(loc)) {
                    is Either.Left -> g
                    is Either.Right ->
                        Either.Right(g.value.describe(loc.workerDf.sessionId, loc.workerDf.dfName, parentAllocator))
                }
            else -> Either.Left(CharonError.NotYetImplemented(plan.rpc))
        }
    }

    private fun describeDbTable(db: DbTable): Either<CharonError, DescribeResult> {
        val reg = connectionRegistry ?: return Either.Left(CharonError.UnknownConnectionId(db.connectionId))
        val reader = dbReader ?: return Either.Left(CharonError.EndpointUnavailable("db provider not configured"))
        return when (val auth = reg.authorize(db.connectionId, DbOp.READ, db.schema)) {
            is Either.Left -> auth
            is Either.Right ->
                try {
                    Either.Right(reader.describe(auth.value, db.schema, db.table))
                } catch (e: InvalidIdentifierException) {
                    Either.Left(CharonError.InvalidIdentifier(e.detail))
                } catch (e: SQLException) {
                    Either.Left(CharonError.EndpointUnavailable("${db.connectionId}: ${e.message}"))
                }
        }
    }

    /**
     * The shared body for Materialize and Copy — both verbs pump bytes
     * through `ArrowPipe`; the only difference is the metric label
     * (`rpc=materialize` vs `rpc=copy`) for [architecture §8] observability.
     */
    private fun copyOrMaterialize(plan: Plan): Either<CharonError, MoveResult> {
        val src = plan.source
        val tgt =
            plan.target
                ?: return Either.Left(
                    CharonError.IllegalTargetForRpc(plan.rpc, src.kind()),
                )
        val started = System.nanoTime()
        return when {
            src.hasSeaweed() && tgt.hasSeaweed() -> {
                val srcBlob = src.seaweed
                val tgtBlob = tgt.seaweed
                // Same-location no-op: identical bucket + key.
                if (srcBlob.bucket == tgtBlob.bucket && srcBlob.key == tgtBlob.key) {
                    val head = s3Client.headObject { it.bucket(srcBlob.bucket).key(srcBlob.key) }
                    val fp = head.metadata()?.get(SeaweedEndpoint.SCHEMA_FP_METADATA_KEY).orEmpty()
                    Either.Right(
                        MoveResult
                            .newBuilder()
                            .setTarget(tgt)
                            .setSchemaFingerprint(fp)
                            .setSchemaJson("")
                            .setRowCount(-1L)
                            .setSizeBytes(head.contentLength())
                            .setDurationMs(0)
                            .build()
                            .also { recordMetrics(plan.rpc, src.kind(), tgt.kind(), "same_location", started) },
                    )
                } else {
                    val source = SeaweedEndpoint(s3Client, parentAllocator).apply { setLocation(srcBlob) }
                    val target = SeaweedEndpoint(s3Client, parentAllocator).apply { setLocation(tgtBlob) }
                    val result = pipeWithOptions(plan, src, tgt, source, target)
                    recordMetrics(plan.rpc, src.kind(), tgt.kind(), resultLabel(result), started)
                    result
                }
            }
            src.hasSeaweed() && tgt.hasRedis() -> {
                val srcBlob = src.seaweed
                val tgtEntry = tgt.redis
                val source = SeaweedEndpoint(s3Client, parentAllocator).apply { setLocation(srcBlob) }
                val target =
                    RedisEndpoint(
                        redisOps,
                        redisMaxValueBytes,
                        redisDefaultTtlSeconds,
                        parentAllocator,
                    ).apply {
                        setLocation(tgtEntry)
                    }
                val result = pipeWithOptions(plan, src, tgt, source, target)
                recordMetrics(plan.rpc, src.kind(), tgt.kind(), resultLabel(result), started)
                result
            }
            src.hasRedis() && tgt.hasSeaweed() -> {
                val srcEntry = src.redis
                val tgtBlob = tgt.seaweed
                val source =
                    RedisEndpoint(
                        redisOps,
                        redisMaxValueBytes,
                        redisDefaultTtlSeconds,
                        parentAllocator,
                    ).apply {
                        setLocation(srcEntry)
                    }
                val target = SeaweedEndpoint(s3Client, parentAllocator).apply { setLocation(tgtBlob) }
                val result = pipeWithOptions(plan, src, tgt, source, target)
                recordMetrics(plan.rpc, src.kind(), tgt.kind(), resultLabel(result), started)
                result
            }
            src.hasRedis() && tgt.hasRedis() -> {
                val srcEntry = src.redis
                val tgtEntry = tgt.redis
                if (srcEntry.key == tgtEntry.key) {
                    // Same-location no-op: PTTL + sidecar fingerprint
                    // readback (no I/O write). The pipe would do the
                    // same dance (PTTL + STRLEN) via Describe; for the
                    // move path we return a MoveResult with the sidecar
                    // fingerprint. The size is the current STRLEN.
                    val fp =
                        redisOps
                            .get(RedisEndpoint.keyOf(RedisEndpoint.fpKey(srcEntry.key)))
                            ?.toString(Charsets.UTF_8)
                            .orEmpty()
                    val size = redisOps.strlen(RedisEndpoint.keyOf(srcEntry.key))
                    Either.Right(
                        MoveResult
                            .newBuilder()
                            .setTarget(tgt)
                            .setSchemaFingerprint(fp)
                            .setSchemaJson("")
                            .setRowCount(-1L)
                            .setSizeBytes(size)
                            .setDurationMs(0)
                            .build()
                            .also { recordMetrics(plan.rpc, src.kind(), tgt.kind(), "same_location", started) },
                    )
                } else {
                    val source =
                        RedisEndpoint(
                            redisOps,
                            redisMaxValueBytes,
                            redisDefaultTtlSeconds,
                            parentAllocator,
                        ).apply {
                            setLocation(srcEntry)
                        }
                    val target =
                        RedisEndpoint(
                            redisOps,
                            redisMaxValueBytes,
                            redisDefaultTtlSeconds,
                            parentAllocator,
                        ).apply {
                            setLocation(tgtEntry)
                        }
                    val result = pipeWithOptions(plan, src, tgt, source, target)
                    recordMetrics(plan.rpc, src.kind(), tgt.kind(), resultLabel(result), started)
                    result
                }
            }
            // Any pair involving a DB table (Phase 2) or a worker DF (Phase 3) —
            // db extract/ingest, db→db, worker scan-out, X→worker stage-in.
            src.hasDbTable() || tgt.hasDbTable() || src.hasWorkerDf() || tgt.hasWorkerDf() -> {
                val result = genericMove(plan, src, tgt)
                recordMetrics(plan.rpc, src.kind(), tgt.kind(), resultLabel(result), started)
                result
            }
            else -> {
                recordMetrics(plan.rpc, src.kind(), tgt.kind(), "unimplemented", started)
                Either.Left(CharonError.NotYetImplemented(plan.rpc))
            }
        }
    }

    /**
     * The DB-edge move path (Stage 2.1 extract + Stage 2.2 ingest). Builds the
     * source + target endpoints (allow-list enforced *before* any I/O), pumps
     * through [ArrowPipe], and maps the typed DB exceptions
     * ([UnmappableTypeException] → `FAILED_PRECONDITION`,
     * [DbWritePreconditionException] → `FAILED_PRECONDITION`, `SQLException` →
     * `UNAVAILABLE`). No partial write: the writer runs one transaction and
     * [ArrowPipe] calls `discard` (ROLLBACK) on any fault.
     */
    private fun genericMove(
        plan: Plan,
        src: Location,
        tgt: Location,
    ): Either<CharonError, MoveResult> {
        val source =
            when (val s = buildSource(src, plan.options.chunkRows.takeIf { it > 0 } ?: 65536)) {
                is Either.Left -> return s
                is Either.Right -> s.value
            }
        val target =
            when (val t = buildTarget(tgt, plan.options)) {
                is Either.Left -> return t
                is Either.Right -> t.value
            }
        return try {
            pipeWithOptions(plan, src, tgt, source, target)
        } catch (e: InvalidIdentifierException) {
            Either.Left(CharonError.InvalidIdentifier(e.detail))
        } catch (e: UnmappableTypeException) {
            Either.Left(CharonError.UnmappableType(e.column, e.detail))
        } catch (e: DbWritePreconditionException) {
            Either.Left(CharonError.DbWritePrecondition(e.schemaName, e.table, e.detail))
        } catch (e: WorkerOpUnsupportedException) {
            Either.Left(CharonError.WorkerOpUnsupported(e.engine.name, e.op, e.detail))
        } catch (e: WorkerDfNotFoundException) {
            Either.Left(CharonError.SourceNotFound(LocationKind.WORKER_DF, e.ref))
        } catch (e: SQLException) {
            Either.Left(CharonError.EndpointUnavailable("db: ${e.message}"))
        } catch (e: io.grpc.StatusException) {
            Either.Left(mapGrpcStatus(e.status))
        } catch (e: io.grpc.StatusRuntimeException) {
            Either.Left(mapGrpcStatus(e.status))
        }
    }

    /** Map a worker gRPC status to a typed [CharonError]: a worker
     *  `RESOURCE_EXHAUSTED` (e.g. `workspace_cap_exceeded`) → [CharonError.WorkerResourceExhausted];
     *  `NOT_FOUND` → [CharonError.SourceNotFound]; else [CharonError.EndpointUnavailable]. */
    private fun mapGrpcStatus(status: io.grpc.Status): CharonError =
        when (status.code) {
            io.grpc.Status.Code.RESOURCE_EXHAUSTED ->
                CharonError.WorkerResourceExhausted(status.description ?: "worker resource limit reached")
            io.grpc.Status.Code.NOT_FOUND ->
                CharonError.SourceNotFound(
                    LocationKind.WORKER_DF,
                    status.description ?: "",
                )
            else -> CharonError.EndpointUnavailable("worker: ${status.code} ${status.description ?: ""}")
        }

    /** Resolve the worker gateway for a `worker_df` location's engine. */
    private fun workerGatewayFor(loc: Location): Either<CharonError, WorkerGateway> {
        val factory =
            workerGatewayFactory
                ?: return Either.Left(CharonError.WorkerEngineUnavailable(loc.workerDf.workerKind.name))
        return factory.forKind(loc.workerDf.workerKind)?.let { Either.Right(it) }
            ?: Either.Left(CharonError.WorkerEngineUnavailable(loc.workerDf.workerKind.name))
    }

    /** Build a [Source] for a location (DB source enforces READ allow-list). */
    private fun buildSource(
        loc: Location,
        chunkRows: Int,
    ): Either<CharonError, Source> =
        when {
            loc.hasSeaweed() ->
                Either.Right(SeaweedEndpoint(s3Client, parentAllocator).apply { setLocation(loc.seaweed) })
            loc.hasRedis() ->
                Either.Right(
                    RedisEndpoint(redisOps, redisMaxValueBytes, redisDefaultTtlSeconds, parentAllocator)
                        .apply { setLocation(loc.redis) },
                )
            loc.hasDbTable() -> {
                val db = loc.dbTable
                val reg = connectionRegistry ?: return Either.Left(CharonError.UnknownConnectionId(db.connectionId))
                val reader =
                    dbReader ?: return Either.Left(CharonError.EndpointUnavailable("db provider not configured"))
                when (val auth = reg.authorize(db.connectionId, DbOp.READ, db.schema)) {
                    is Either.Left -> auth
                    is Either.Right -> Either.Right(reader.source(auth.value, db.schema, db.table, chunkRows))
                }
            }
            loc.hasWorkerDf() ->
                when (val g = workerGatewayFor(loc)) {
                    is Either.Left -> g
                    is Either.Right ->
                        Either.Right(
                            WorkerEndpoint(
                                g.value,
                                loc.workerDf.sessionId,
                                loc.workerDf.dfName,
                                parentAllocator = parentAllocator,
                            ),
                        )
                }
            else -> Either.Left(CharonError.NotYetImplemented(MoveRpc.COPY))
        }

    /** Build a [Target] for a location (DB target enforces WRITE allow-list +
     *  carries the `db_write_mode` from the request). */
    private fun buildTarget(
        loc: Location,
        options: org.tatrman.charon.v1.MoveOptions,
    ): Either<CharonError, Target> =
        when {
            loc.hasSeaweed() ->
                Either.Right(SeaweedEndpoint(s3Client, parentAllocator).apply { setLocation(loc.seaweed) })
            loc.hasRedis() ->
                Either.Right(
                    RedisEndpoint(redisOps, redisMaxValueBytes, redisDefaultTtlSeconds, parentAllocator)
                        .apply { setLocation(loc.redis) },
                )
            loc.hasDbTable() -> {
                val db = loc.dbTable
                val reg = connectionRegistry ?: return Either.Left(CharonError.UnknownConnectionId(db.connectionId))
                val writer =
                    dbWriter ?: return Either.Left(CharonError.EndpointUnavailable("db provider not configured"))
                when (val auth = reg.authorize(db.connectionId, DbOp.WRITE, db.schema)) {
                    is Either.Left -> auth
                    is Either.Right ->
                        Either.Right(writer.target(auth.value, db.schema, db.table, options.dbWriteMode))
                }
            }
            loc.hasWorkerDf() ->
                when (val g = workerGatewayFor(loc)) {
                    is Either.Left -> g
                    is Either.Right ->
                        Either.Right(
                            WorkerEndpoint(
                                g.value,
                                loc.workerDf.sessionId,
                                loc.workerDf.dfName,
                                expectedFingerprint = options.expectedSchemaFingerprint.takeIf { it.isNotEmpty() },
                                parentAllocator = parentAllocator,
                            ),
                        )
                }
            else -> Either.Left(CharonError.NotYetImplemented(MoveRpc.COPY))
        }

    private fun pipeWithOptions(
        plan: Plan,
        src: org.tatrman.charon.v1.Location,
        tgt: org.tatrman.charon.v1.Location,
        source: org.tatrman.kantheon.charon.core.Source,
        target: org.tatrman.kantheon.charon.core.Target,
    ): Either<CharonError, MoveResult> {
        val options =
            PipeOptions(
                chunkRows = plan.options.chunkRows.takeIf { it > 0 } ?: 65536,
                maxBytes =
                    when {
                        // The per-move byte cap. If the request
                        // specifies one, use it (subject to the
                        // server's own max). If not, the endpoint's
                        // own cap applies: Redis targets are bounded
                        // at `charon.redis.max-value-bytes` (per
                        // review-006 R4 — the Stage 1.3 default of
                        // 128 MiB was unsafe for Redis).
                        plan.options.maxBytes.takeIf { it > 0 } != null -> plan.options.maxBytes
                        tgt.hasRedis() -> redisMaxValueBytes
                        else -> 128L * 1024L * 1024L
                    },
                expectedSchemaFingerprint =
                    plan.options.expectedSchemaFingerprint.takeIf { it.isNotEmpty() },
            )
        val result = ArrowPipe.pipe(plan.rpc, source, target, src, tgt, options)
        // Dedicated fingerprint-drift counter (architecture §8 / Stage 3.2 T5) —
        // the signal Pythia's PD-5 drift detection is the consumer of. The move
        // counter labels result=error generally; this isolates the drift case.
        if (result is Either.Left && result.value is CharonError.FingerprintMismatch) {
            Counter
                .builder("charon_fingerprint_mismatch_total")
                .tags("source", src.kind().name.lowercase(), "target", tgt.kind().name.lowercase())
                .register(registry)
                .increment()
        }
        return result
    }

    private fun resultLabel(result: Either<CharonError, MoveResult>): String =
        when (result) {
            is Either.Right -> "ok"
            is Either.Left -> "error"
        }

    private fun recordMetrics(
        rpc: MoveRpc,
        source: LocationKind,
        target: LocationKind,
        outcome: String,
        startedAt: Long,
    ) {
        val tags =
            arrayOf(
                "rpc",
                rpc.name.lowercase(),
                "source",
                source.name.lowercase(),
                "target",
                target.name.lowercase(),
                "result",
                outcome,
            )
        Counter
            .builder("charon_moves_total")
            .tags(*tags)
            .register(registry)
            .increment()
        Timer
            .builder("charon_move_duration_ms")
            .tags("rpc", rpc.name.lowercase(), "source", source.name.lowercase(), "target", target.name.lowercase())
            .publishPercentileHistogram()
            .register(registry)
            .record(java.time.Duration.ofNanos(System.nanoTime() - startedAt))
    }
}
