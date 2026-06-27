package org.tatrman.kantheon.echo.config

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.*

data class AppConfig(
    val serverPort: Int,
    val grpcPort: Int,
    val grpcReflectionEnabled: Boolean,
    val refreshIntervalSeconds: Long,
    val tokenBasedConfig: TokenBasedConfig = TokenBasedConfig(),
    val nlp: NlpConfig = NlpConfig(),
    val loaderSource: LoaderSourceConfig = LoaderSourceConfig(),
    val metadata: MetadataConfig = MetadataConfig(),
    /**
     * Warehouse connection for the `metadata` loader source. Null for the
     * default `static` (in-repo JSON catalog) source, which needs no DB.
     * Required (non-null) when `loaderSource.source = "metadata"` — the loader
     * composes `SELECT pk, col FROM table` per fuzzy column and runs it here.
     */
    val database: DatabaseConfig? = null,
)

/**
 * Warehouse dialect + connection for the metadata loader. The sealed pair
 * (`PostgresConfig` / `MssqlConfig`) drives both the JDBC URL in
 * [org.tatrman.kantheon.echo.db.DatabaseFactory] and the identifier quoting in
 * the SQL composer (`"x"` for Postgres, `[x]` for MSSQL).
 */
sealed interface DatabaseConfig {
    val user: String
    val pass: String
}

data class PostgresConfig(
    val host: String,
    val port: Int,
    val database: String,
    override val user: String,
    override val pass: String,
) : DatabaseConfig

data class MssqlConfig(
    val host: String,
    val port: Int,
    val database: String,
    override val user: String,
    override val pass: String,
) : DatabaseConfig

data class LoaderSourceConfig(
    val source: String = "static",
)

data class MetadataConfig(
    val host: String = "ariadne",
    val port: Int = 7261,
    val timeoutMs: Long = 10_000,
    val schema: String = "db",
    /**
     * Source-identifier guard for the v1 single-source assumption. When
     * set, the loader skips any fuzzy column whose `QualifiedName.namespace`
     * differs from this value and records `fuzzy_loader_skipped_total{reason="wrong_source"}`.
     * Empty string disables the check — the historical v1 "single source,
     * asserted not solved" behaviour. Multi-source deployments should set
     * this explicitly.
     */
    val namespace: String = "",
)

data class TokenBasedConfig(
    val distanceThreshold: Double = 0.20,
    val orderBonusMultiplier: Double = 1.05,
    val maxOrderBonus: Double = 1.5,
    val idfEnabled: Boolean = true,
)

/** Kadmos (Phase 2.3) integration for Czech lemmatisation. Disabled → folded-surface matching only. */
data class NlpConfig(
    val enabled: Boolean = false,
    val host: String = "kadmos",
    val port: Int = 7270,
    val timeoutMs: Long = 5_000,
    val lang: String = "cs",
) {
    val baseUrl: String get() = "http://$host:$port"
}

object ConfigLoader {
    fun load(): AppConfig {
        val config = ConfigFactory.load()
        val echoConfig = config.getConfig("echo")

        return AppConfig(
            serverPort = config.getString("ktor.deployment.port").toInt(),
            grpcPort = echoConfig.getString("grpc.port").toInt(),
            grpcReflectionEnabled =
                echoConfig.hasPath("grpc.reflection-enabled") &&
                    echoConfig.getBoolean("grpc.reflection-enabled"),
            refreshIntervalSeconds = echoConfig.getLong("refreshIntervalSeconds"),
            tokenBasedConfig = loadTokenBasedConfig(echoConfig),
            nlp = loadNlpConfig(echoConfig),
            loaderSource = loadLoaderSourceConfig(echoConfig),
            metadata = loadMetadataConfig(echoConfig),
            database = loadDatabaseConfig(echoConfig),
        )
    }

    /**
     * Reads the warehouse connection from `echo.type` + `echo.{postgres,mssql}`.
     * Returns null when `echo.type` is absent — the `static` (JSON catalog)
     * source needs no DB, so a DB-less config is valid. When the `metadata`
     * source is selected but this is null, `Application.module` fails fast.
     */
    private fun loadDatabaseConfig(echoConfig: com.typesafe.config.Config): DatabaseConfig? {
        if (!echoConfig.hasPath("type")) return null
        return when (echoConfig.getString("type").uppercase()) {
            "POSTGRES" -> {
                val pg = echoConfig.getConfig("postgres")
                PostgresConfig(
                    host = pg.getString("host"),
                    port = pg.getString("port").toInt(),
                    database = pg.getString("database"),
                    user = pg.getString("user"),
                    pass = pg.getString("password"),
                )
            }
            "MSSQL" -> {
                val ms = echoConfig.getConfig("mssql")
                MssqlConfig(
                    host = ms.getString("host"),
                    port = ms.getString("port").toInt(),
                    database = ms.getString("database"),
                    user = ms.getString("user"),
                    pass = ms.getString("password"),
                )
            }
            else -> throw IllegalArgumentException(
                "Unknown echo.type: '${echoConfig.getString("type")}' (expected postgres|mssql)",
            )
        }
    }

    private fun loadNlpConfig(echoConfig: com.typesafe.config.Config): NlpConfig =
        try {
            val nlp = echoConfig.getConfig("nlp")
            val defaults = NlpConfig()
            NlpConfig(
                enabled = if (nlp.hasPath("enabled")) nlp.getBoolean("enabled") else defaults.enabled,
                host = if (nlp.hasPath("host")) nlp.getString("host") else defaults.host,
                port = if (nlp.hasPath("port")) nlp.getString("port").toInt() else defaults.port,
                timeoutMs = if (nlp.hasPath("timeout-ms")) nlp.getLong("timeout-ms") else defaults.timeoutMs,
                lang = if (nlp.hasPath("lang")) nlp.getString("lang") else defaults.lang,
            )
        } catch (e: com.typesafe.config.ConfigException) {
            NlpConfig()
        }

    private fun loadTokenBasedConfig(echoConfig: com.typesafe.config.Config): TokenBasedConfig =
        try {
            val tokenBasedConfig = echoConfig.getConfig("token-based")
            TokenBasedConfig(
                distanceThreshold = tokenBasedConfig.getDouble("distance-threshold"),
                orderBonusMultiplier = tokenBasedConfig.getDouble("order-bonus-multiplier"),
                maxOrderBonus = tokenBasedConfig.getDouble("max-order-bonus"),
                idfEnabled =
                    if (tokenBasedConfig.hasPath("idf-enabled")) {
                        tokenBasedConfig.getBoolean("idf-enabled")
                    } else {
                        true
                    },
            )
        } catch (e: com.typesafe.config.ConfigException) {
            TokenBasedConfig()
        }

    private fun loadLoaderSourceConfig(echoConfig: com.typesafe.config.Config): LoaderSourceConfig =
        try {
            val loader = echoConfig.getConfig("loader")
            LoaderSourceConfig(
                source = if (loader.hasPath("source")) loader.getString("source") else "static",
            )
        } catch (e: com.typesafe.config.ConfigException) {
            LoaderSourceConfig()
        }

    private fun loadMetadataConfig(echoConfig: com.typesafe.config.Config): MetadataConfig =
        try {
            val metadata = echoConfig.getConfig("metadata")
            val defaults = MetadataConfig()
            MetadataConfig(
                host = if (metadata.hasPath("host")) metadata.getString("host") else defaults.host,
                port = if (metadata.hasPath("port")) metadata.getInt("port") else defaults.port,
                timeoutMs = if (metadata.hasPath("timeout-ms")) metadata.getLong("timeout-ms") else defaults.timeoutMs,
                schema = if (metadata.hasPath("schema")) metadata.getString("schema") else defaults.schema,
                namespace = if (metadata.hasPath("namespace")) metadata.getString("namespace") else defaults.namespace,
            )
        } catch (e: com.typesafe.config.ConfigException) {
            MetadataConfig()
        }
}
