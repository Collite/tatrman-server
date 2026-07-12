// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.config

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
 * [org.tatrman.fuzzy.db.DatabaseFactory] and the identifier quoting in
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
    val host: String = "veles",
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

/**
 * `ttr-nlp` integration for Czech lemmatisation via gRPC `BatchLemmatize`
 * (RG-P2.S1.T4). Disabled → folded-surface matching only. `port` is the nlp
 * **gRPC** port (7271; gRPC is ttr-nlp's contract, REST is its dev mirror).
 */
data class NlpConfig(
    val enabled: Boolean = false,
    val host: String = "nlp",
    val port: Int = 7271,
    val timeoutMs: Long = 5_000,
    val lang: String = "cs",
)

object ConfigLoader {
    fun load(): AppConfig {
        val config = ConfigFactory.load()
        val fuzzyConfig = config.getConfig("fuzzy")

        return AppConfig(
            serverPort = config.getString("ktor.deployment.port").toInt(),
            grpcPort = fuzzyConfig.getString("grpc.port").toInt(),
            grpcReflectionEnabled =
                fuzzyConfig.hasPath("grpc.reflection-enabled") &&
                    fuzzyConfig.getBoolean("grpc.reflection-enabled"),
            refreshIntervalSeconds = fuzzyConfig.getLong("refreshIntervalSeconds"),
            tokenBasedConfig = loadTokenBasedConfig(fuzzyConfig),
            nlp = loadNlpConfig(fuzzyConfig),
            loaderSource = loadLoaderSourceConfig(fuzzyConfig),
            metadata = loadMetadataConfig(fuzzyConfig),
            database = loadDatabaseConfig(fuzzyConfig),
        )
    }

    /**
     * Reads the warehouse connection from `fuzzy.type` + `fuzzy.{postgres,mssql}`.
     * Returns null when `fuzzy.type` is absent — the `static` (JSON catalog)
     * source needs no DB, so a DB-less config is valid. When the `metadata`
     * source is selected but this is null, `Application.module` fails fast.
     */
    private fun loadDatabaseConfig(fuzzyConfig: com.typesafe.config.Config): DatabaseConfig? {
        if (!fuzzyConfig.hasPath("type")) return null
        return when (fuzzyConfig.getString("type").uppercase()) {
            "POSTGRES" -> {
                val pg = fuzzyConfig.getConfig("postgres")
                PostgresConfig(
                    host = pg.getString("host"),
                    port = pg.getString("port").toInt(),
                    database = pg.getString("database"),
                    user = pg.getString("user"),
                    pass = pg.getString("password"),
                )
            }
            "MSSQL" -> {
                val ms = fuzzyConfig.getConfig("mssql")
                MssqlConfig(
                    host = ms.getString("host"),
                    port = ms.getString("port").toInt(),
                    database = ms.getString("database"),
                    user = ms.getString("user"),
                    pass = ms.getString("password"),
                )
            }
            else -> throw IllegalArgumentException(
                "Unknown fuzzy.type: '${fuzzyConfig.getString("type")}' (expected postgres|mssql)",
            )
        }
    }

    private fun loadNlpConfig(fuzzyConfig: com.typesafe.config.Config): NlpConfig =
        try {
            val nlp = fuzzyConfig.getConfig("nlp")
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

    private fun loadTokenBasedConfig(fuzzyConfig: com.typesafe.config.Config): TokenBasedConfig =
        try {
            val tokenBasedConfig = fuzzyConfig.getConfig("token-based")
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

    private fun loadLoaderSourceConfig(fuzzyConfig: com.typesafe.config.Config): LoaderSourceConfig =
        try {
            val loader = fuzzyConfig.getConfig("loader")
            LoaderSourceConfig(
                source = if (loader.hasPath("source")) loader.getString("source") else "static",
            )
        } catch (e: com.typesafe.config.ConfigException) {
            LoaderSourceConfig()
        }

    private fun loadMetadataConfig(fuzzyConfig: com.typesafe.config.Config): MetadataConfig =
        try {
            val metadata = fuzzyConfig.getConfig("metadata")
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
