package org.tatrman.kantheon.charon.endpoints

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import java.io.ByteArrayInputStream
import java.time.Instant
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.types.pojo.Schema
import org.slf4j.LoggerFactory
import org.tatrman.transfer.v1.DescribeResult
import org.tatrman.transfer.v1.EvictResult
import org.tatrman.transfer.v1.RedisEntry
import org.tatrman.kantheon.charon.core.Integrity
import org.tatrman.kantheon.charon.core.LocationKind
import org.tatrman.kantheon.charon.core.Source
import org.tatrman.kantheon.charon.core.Target

private val log = LoggerFactory.getLogger(RedisEndpoint::class.java)

/**
 * The narrow Redis command surface Charon's [RedisEndpoint] needs.
 *
 * `RedisCommands<K,V>` is a 19-inheritance-deep union of every Redis
 * command group (string, hash, sorted set, stream, JSON, vector, …)
 * and proxies it via mockk/byte-buddy blow the JVM method limit
 * (`Method[] too large`). The endpoint only needs six commands: get,
 * set, del, exists, strlen, pttl — so we declare them on a small
 * interface and have Lettuce's `RedisCommands` fulfil it via a thin
 * default impl.
 *
 * Tests mock this interface directly; production uses [LettuceRedisOps]
 * which delegates to a `StatefulRedisConnection<ByteArray, ByteArray>`.
 */
interface RedisOps {
    fun get(key: ByteArray): ByteArray?

    fun set(
        key: ByteArray,
        value: ByteArray,
        args: SetArgs,
    ): String

    fun del(vararg keys: ByteArray): Long

    fun exists(vararg keys: ByteArray): Long

    fun strlen(key: ByteArray): Long

    fun pttl(key: ByteArray): Long
}

/** The production impl — thin delegates onto Lettuce's sync commands. */
class LettuceRedisOps(
    private val connection: StatefulRedisConnection<ByteArray, ByteArray>,
) : RedisOps {
    private val sync get() = connection.sync()

    override fun get(key: ByteArray): ByteArray? = sync.get(key)

    override fun set(
        key: ByteArray,
        value: ByteArray,
        args: SetArgs,
    ): String = sync.set(key, value, args)

    override fun del(vararg keys: ByteArray): Long = sync.del(*keys)

    override fun exists(vararg keys: ByteArray): Long = sync.exists(*keys)

    override fun strlen(key: ByteArray): Long = sync.strlen(key)

    override fun pttl(key: ByteArray): Long = sync.pttl(key)
}

/**
 * Redis-backed endpoint for the platform Redis (charon/architecture.md §2).
 *
 * **Atomicity strategy.** Redis `SET key value EX ttl` is a single atomic
 * command; no temp-key dance is needed (architecture §5 "no-partial-write"
 * invariant). The fingerprint rides alongside the value as a sidecar key
 * `[key]:schema-fp` with the same TTL — set together via Lettuce's sync
 * API (each SET is a single Redis command; the server applies them
 * sequentially within the same connection, and Redis guarantees that
 * a `SET … EX` is atomic for the value itself).
 *
 * **Sidecar convention.** The schema fingerprint is stored at `[key]:schema-fp`
 * (Lettuce synchronous GET). A missing sidecar is drift: the pipe treats it
 * as `SourceNotFound` because the row is incomplete.
 *
 * **Drift detection.** [open] returns `null` if either the value or the
 * sidecar is missing — the move never starts. A DELETE of one without the
 * other is the failure mode the sidecar guards against.
 */
class RedisEndpoint(
    private val ops: RedisOps,
    /** The per-move byte cap mirrored from `charon.redis.max-value-bytes`
     *  (contracts §8). Surfaced as a field for the executor's
     *  `CharonError.ByteCapExceeded` translation. */
    val maxValueBytes: Long = 64L * 1024L * 1024L,
    /** The fallback TTL (in seconds) for a `RedisEntry` whose
     *  `ttl_seconds` is unset. Mirrored from `charon.redis.default-ttl-s`
     *  (contracts §8). A `null` default means "no expiry" (discouraged
     *  but supported; the contracts doc warns against it). */
    val defaultTtlSeconds: Long? = 86_400L,
    /** A parent allocator used by both [open] (the source's
     *  reader) and [begin] (the target's owned per-batch
     *  roots + the commit-time writer). Both source and
     *  target of a move must share a root allocator
     *  (Arrow's `TransferPair` requirement). The default
     *  is a fresh `RootAllocator` per endpoint instance —
     *  tests pass the same parent to the executor's source
     *  and target. */
    parentAllocator: org.apache.arrow.memory.RootAllocator? = null,
) : Source,
    Target {
    private val parentAllocator: org.apache.arrow.memory.RootAllocator =
        parentAllocator ?: org.apache.arrow.memory
            .RootAllocator()

    /** Convenience constructor for the production wiring — wraps a
     *  Lettuce connection in [LettuceRedisOps]. */
    constructor(
        connection: StatefulRedisConnection<ByteArray, ByteArray>,
        maxValueBytes: Long = 64L * 1024L * 1024L,
        defaultTtlSeconds: Long? = 86_400L,
    ) : this(LettuceRedisOps(connection), maxValueBytes, defaultTtlSeconds)

    private var currentLocation: RedisEntry? = null

    // --- Source ---

    override fun open(): ArrowStreamReader? {
        val loc = currentLocation ?: error("open() called before setLocation")
        val value = ops.get(keyOf(loc.key)) ?: return null
        // Drift guard: a value with no sidecar fingerprint is incomplete.
        // Treat as missing.
        val fp = ops.get(keyOf(fpKey(loc.key))) ?: return null
        log.debug(
            "open: redis key {} ({} bytes, fp {}...)",
            loc.key,
            value.size,
            String(fp).take(8),
        )
        // The reader allocates under the shared parent allocator and is
        // closed by the pipe's `use {}`. No per-move child here — a
        // child would leak (the source has no discard hook to close it);
        // the parent is the shared root the target's owned roots also
        // live under, so `TransferPair` still works (review-006 R8.6).
        return ArrowStreamReader(ByteArrayInputStream(value), parentAllocator)
    }

    override fun kind(): LocationKind = LocationKind.REDIS

    override fun ref(): String = currentLocation?.key ?: "<unset>"

    // --- Target (three-phase write) ---

    override fun begin(schema: Schema): Any {
        val loc = currentLocation ?: error("begin() called before setLocation")
        val ttl =
            when {
                loc.hasTtlSeconds() && loc.ttlSeconds > 0 -> loc.ttlSeconds
                defaultTtlSeconds != null && defaultTtlSeconds > 0 -> defaultTtlSeconds
                else -> null
            }
        return RedisWriteReceipt(
            key = loc.key,
            ttlSeconds = ttl,
            schema = schema,
            // Per-move child allocator under the endpoint's
            // parent (so the source's reader and the target's
            // owned batch roots share a root — required by
            // Arrow's TransferPair).
            allocator =
                parentAllocator.newChildAllocator("redis-write-${loc.key}", 0, Long.MAX_VALUE),
        )
    }

    override fun writeBatch(
        receipt: Any,
        root: VectorSchemaRoot,
    ) {
        if (root.rowCount == 0) return
        val r = receipt as RedisWriteReceipt
        // R1.2: materialise the batch into an owned root. The
        // reader's reused root (the pipe's `src.vectorSchemaRoot`)
        // reloads on every `loadNextBatch()` call, so a stored
        // reference would have rowCount == 0 by `commit()` time.
        val owned = VectorSchemaRoot.create(r.schema, r.allocator)
        try {
            for (i in 0 until r.schema.fields.size) {
                val srcVec = root.getVector(i)
                val dstVec = owned.getVector(i)
                dstVec.reAlloc()
                srcVec.makeTransferPair(dstVec).transfer()
            }
            owned.rowCount = root.rowCount
            r.pendingBatches.add(owned)
        } catch (e: Exception) {
            owned.close()
            throw e
        }
    }

    override fun commit(receipt: Any): Any {
        val r = receipt as RedisWriteReceipt
        val bytes =
            try {
                SeaweedEndpoint.serializeBatchesToIpcStream(r.schema, r.pendingBatches, r.allocator)
            } catch (e: Exception) {
                r.closeAll()
                r.allocator.close()
                throw e
            }
        val fp = Integrity.fingerprint(r.schema)
        val setArgs =
            r.ttlSeconds?.let { SetArgs.Builder.ex(it) }
                ?: SetArgs() // no TTL: SET key value (no EX)
        // Two SETs back-to-back on the same connection. Each is atomic at
        // the server; the value + sidecar land with the same TTL. If the
        // second SET fails the value is still visible (so the pipe's
        // discard() must also DEL the value — which it does, see
        // [discard]).
        try {
            val valueResp = ops.set(keyOf(r.key), bytes, setArgs)
            val fpResp = ops.set(keyOf(fpKey(r.key)), fp.toByteArray(Charsets.UTF_8), setArgs)
            log.debug(
                "commit: redis key {} ({} bytes, value={}, fp={}, ttl={}s)",
                r.key,
                bytes.size,
                valueResp,
                fpResp,
                r.ttlSeconds,
            )
        } catch (e: Exception) {
            r.closeAll()
            r.allocator.close()
            throw e
        }
        r.closeAll()
        r.allocator.close()
        return CommitReceipt(key = r.key)
    }

    override fun discard(receipt: Any) {
        val r = receipt as? RedisWriteReceipt ?: return
        try {
            val delCount = ops.del(keyOf(r.key), keyOf(fpKey(r.key)))
            log.debug("discard: redis key {} (del {} keys)", r.key, delCount)
        } catch (e: Exception) {
            log.debug("discard: redis del failed for {}: {}", r.key, e.message)
        }
        r.closeAll()
        r.allocator.close()
    }

    // --- Standalone ops ---

    fun describe(entry: RedisEntry): DescribeResult {
        val builder = DescribeResult.newBuilder()
        val present = ops.exists(keyOf(entry.key)) > 0
        if (!present) {
            return builder
                .setExists(false)
                .setRowCount(-1L)
                .setSizeBytes(-1L)
                .build()
        }
        val fp = ops.get(keyOf(fpKey(entry.key)))?.toString(Charsets.UTF_8).orEmpty()
        val size = ops.strlen(keyOf(entry.key))
        val pttl = ops.pttl(keyOf(entry.key))
        val result =
            builder
                .setExists(true)
                .setSchemaFingerprint(fp)
                .setRowCount(-1L)
                .setRowCountExact(false)
                .setSizeBytes(size)
        if (pttl > 0) {
            // pttl > 0: ms remaining. Render as ISO-8601 instant. The
            // contracts doc says "expires_at" is a string; we use the
            // canonical ISO instant format (e.g. "2026-06-14T12:00:00Z")
            // which the charon-mcp tool layer can re-parse.
            val expiresAt = Instant.now().plusMillis(pttl).toString()
            result.setExpiresAt(expiresAt)
        }
        return result.build()
    }

    fun evict(entry: RedisEntry): EvictResult {
        val existed = ops.exists(keyOf(entry.key)) > 0
        // Idempotent DEL — both keys regardless of prior existence. The
        // contract is that Evict is safe to retry.
        ops.del(keyOf(entry.key), keyOf(fpKey(entry.key)))
        return EvictResult.newBuilder().setExisted(existed).build()
    }

    fun setLocation(loc: RedisEntry) {
        currentLocation = loc
    }

    companion object {
        const val SCHEMA_FP_SIDECAR_SUFFIX = ":schema-fp"

        /** Build a Lettuce [RedisClient] from a `redis://` URL (charon/contracts.md §8). */
        fun clientFromUrl(url: String): RedisClient {
            val uri = RedisURI.create(url)
            return RedisClient.create(uri)
        }

        /** The sidecar key for a value's schema fingerprint. */
        fun fpKey(valueKey: String): String = "$valueKey$SCHEMA_FP_SIDECAR_SUFFIX"

        /** Encode an ASCII [String] key to the `byte[]` shape [ByteArrayCodec] expects. */
        fun keyOf(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)
    }
}

/** Internal write-state — the receipt returned by [RedisEndpoint.begin]
 *  and threaded through `writeBatch` / `commit` / `discard`. The
 *  [allocator] backs the [pendingBatches] materialised roots
 *  (R1.2) and the writer's destination root at commit time
 *  (the [SeaweedEndpoint.serializeBatchesToIpcStream] helper is
 *  shared between S3 and Redis). */
internal data class RedisWriteReceipt(
    val key: String,
    val ttlSeconds: Long?,
    val schema: Schema,
    val allocator: org.apache.arrow.memory.BufferAllocator,
    val pendingBatches: MutableList<VectorSchemaRoot> = mutableListOf(),
) {
    fun fingerprint(): String = Integrity.fingerprint(schema)

    fun closeAll() {
        pendingBatches.forEach { it.close() }
        pendingBatches.clear()
    }
}
