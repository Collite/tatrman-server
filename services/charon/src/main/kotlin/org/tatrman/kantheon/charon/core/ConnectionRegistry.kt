package org.tatrman.kantheon.charon.core

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ConnectionRegistry::class.java)

/**
 * The named-connection registry (charon/contracts.md §4).
 *
 * `DbTable` requests carry **only a `connection_id`**, never credentials. This
 * registry loads `/etc/charon/connections.yaml` (ConfigMap), substitutes
 * `${ENV}` secrets from the environment (sealed-secret-injected; never inline,
 * never logged), and resolves an id to a [ConnectionHandle] carrying the JDBC
 * coordinates + the per-connection allow-list.
 *
 * Two enforcement points, both **before any JDBC is attempted**:
 *   - unknown `connection_id` → [CharonError.UnknownConnectionId] (`INVALID_ARGUMENT`);
 *   - a `read`/`write`/`schema` op the allow-list forbids →
 *     [CharonError.AllowListViolation] (`INVALID_ARGUMENT`).
 *
 * **Pythia's internal Postgres is never listed** (contracts §4) — there is no
 * id to resolve, so a request can't reach it through Charon.
 *
 * Reload is atomic: [refresh] parses the new file fully, then swaps the live
 * map in one [AtomicReference] set (a mid-reload resolve sees old-or-new, never
 * a half-built set).
 */
class ConnectionRegistry private constructor(
    initial: Map<String, ConnectionHandle>,
) {
    private val live = AtomicReference(initial)

    /** Resolve an id to its handle, or [CharonError.UnknownConnectionId]. */
    fun resolve(connectionId: String): Either<CharonError, ConnectionHandle> =
        live.get()[connectionId]?.let { Either.Right(it) }
            ?: Either.Left(CharonError.UnknownConnectionId(connectionId))

    /** Resolve + check the op is allow-listed. [op] is `read` or `write`;
     *  [schema] is checked against the connection's exact schema list. */
    fun authorize(
        connectionId: String,
        op: DbOp,
        schema: String,
    ): Either<CharonError, ConnectionHandle> =
        when (val r = resolve(connectionId)) {
            is Either.Left -> r
            is Either.Right -> {
                val h = r.value
                val allowed =
                    when (op) {
                        DbOp.READ -> h.allow.read
                        DbOp.WRITE -> h.allow.write
                    }
                when {
                    !allowed -> Either.Left(CharonError.AllowListViolation(connectionId, op.name.lowercase()))
                    schema !in h.allow.schemas ->
                        Either.Left(CharonError.AllowListViolation(connectionId, "schema '$schema'"))
                    else -> Either.Right(h)
                }
            }
        }

    /** Atomically reload from [yaml]; replaces the live set on success. */
    fun refresh(yaml: String) {
        val next = parse(yaml)
        live.set(next)
        log.info("connection registry reloaded: {} connection(s)", next.size)
    }

    /** The registered ids (for `/ready` degraded-set reporting). */
    fun ids(): Set<String> = live.get().keys

    companion object {
        private val mapper =
            ObjectMapper(YAMLFactory()).registerKotlinModule().apply {
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                // contracts §4 YAML keys are snake_case (jdbc_url, …).
                propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
            }

        /** Build from a YAML string (the connections.yaml content). */
        fun fromYaml(
            yaml: String,
            env: Map<String, String> = System.getenv(),
        ): ConnectionRegistry = ConnectionRegistry(parse(yaml, env))

        /** Build from already-resolved handles (programmatic; used by tests and
         *  any caller that builds connections without a YAML file). */
        fun of(handles: List<ConnectionHandle>): ConnectionRegistry {
            val byId = handles.associateBy { it.id }
            require(byId.size == handles.size) { "duplicate connection id" }
            return ConnectionRegistry(byId)
        }

        /** Build from a file path; empty/missing file → empty registry (a
         *  pod with no DB connections is valid — blob-only moves still work). */
        fun fromFile(
            path: java.io.File,
            env: Map<String, String> = System.getenv(),
        ): ConnectionRegistry =
            if (path.isFile) {
                fromYaml(path.readText(), env)
            } else {
                log.info("no connection registry at {} — DB edges disabled", path)
                ConnectionRegistry(emptyMap())
            }

        private fun parse(
            yaml: String,
            env: Map<String, String> = System.getenv(),
        ): Map<String, ConnectionHandle> {
            val doc = mapper.readValue<ConnectionsDoc>(yaml)
            // **Lazily-validated, per-connection (plan §4 Stage 2.3).** A
            // connection whose `${ENV}` credential is unresolved (e.g. the
            // sealed secret isn't mounted on this cluster) is **skipped with a
            // warning** — it doesn't crash the registry load, so one broken DB
            // never gates the pod. The rest of the connections still resolve.
            val handles =
                doc.connections.mapNotNull { c ->
                    require(c.id.isNotBlank()) { "connection id must not be blank" }
                    val dialect =
                        when (c.kind.lowercase()) {
                            "postgres", "postgresql" -> DbDialect.POSTGRES
                            "mssql", "sqlserver" -> DbDialect.MSSQL
                            else -> error("connection '${c.id}': unknown kind '${c.kind}'")
                        }
                    try {
                        ConnectionHandle(
                            id = c.id,
                            dialect = dialect,
                            jdbcUrl = substitute(c.jdbcUrl, env),
                            username = substitute(c.username, env),
                            password = substitute(c.password, env),
                            allow =
                                AllowList(
                                    read = c.allow.read,
                                    write = c.allow.write,
                                    schemas = c.allow.schemas.toSet(),
                                ),
                            poolMax = c.pool.max,
                        )
                    } catch (e: UnresolvedCredentialException) {
                        log.warn("connection '{}' skipped (degraded): {}", c.id, e.message)
                        null
                    }
                }
            val byId = handles.associateBy { it.id }
            require(byId.size == handles.size) { "duplicate connection id in registry" }
            return byId
        }

        /** Replace `${VAR}` tokens with `env[VAR]`; a missing var throws
         *  [UnresolvedCredentialException] so the connection is skipped (never
         *  loaded with a blank credential). */
        private fun substitute(
            raw: String,
            env: Map<String, String>,
        ): String =
            Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}").replace(raw) { m ->
                val key = m.groupValues[1]
                env[key] ?: throw UnresolvedCredentialException(key)
            }
    }
}

/** Thrown internally when a `${ENV}` credential token can't be resolved; the
 *  registry catches it and skips (degrades) that one connection. */
class UnresolvedCredentialException(
    val envVar: String,
) : RuntimeException("env var '$envVar' is not set")

/** A read or write op against a connection (allow-list check). */
enum class DbOp {
    READ,
    WRITE,
}

/** The resolved coordinates + allow-list for a named connection. Credentials
 *  live only here, in memory; never logged (no `toString` override leaks). */
data class ConnectionHandle(
    val id: String,
    val dialect: DbDialect,
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val allow: AllowList,
    val poolMax: Int,
) {
    /** Redacted — never print the password. */
    override fun toString(): String = "ConnectionHandle(id=$id, dialect=$dialect, allow=$allow, poolMax=$poolMax)"
}

/** The per-connection allow-list (contracts §4). `schemas` is an exact,
 *  glob-free list at v1. */
data class AllowList(
    val read: Boolean,
    val write: Boolean,
    val schemas: Set<String>,
)

// --- YAML shapes (jackson-bound; defaults keep optional blocks optional) -----

private data class ConnectionsDoc(
    val connections: List<ConnectionEntry> = emptyList(),
)

private data class ConnectionEntry(
    val id: String = "",
    val kind: String = "",
    val jdbcUrl: String = "",
    val username: String = "",
    val password: String = "",
    val allow: AllowEntry = AllowEntry(),
    val pool: PoolEntry = PoolEntry(),
)

private data class AllowEntry(
    val read: Boolean = false,
    val write: Boolean = false,
    val schemas: List<String> = emptyList(),
)

private data class PoolEntry(
    val max: Int = 4,
)
