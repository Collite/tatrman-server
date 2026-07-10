package org.tatrman.query.mcp

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

/** Resolved configuration block for the theseus-mcp service. */
data class QueryMcpConfig(
    val serverPort: Int,
    val mcpTransport: String,
    val mcpPath: String,
    val upstream: Upstream,
    val limits: Limits,
    val security: Security,
    val toolTimeoutsMs: Map<String, Long>,
    /** DF-ME02-CACHE — TTL for in-process caching of attribute decorations in GrpcMetadataClient. */
    val metadataDecorationCacheTtlSeconds: Long = 30L,
) {
    data class GrpcEndpoint(
        val host: String,
        val port: Int,
        val deadlineSeconds: Long,
    )

    data class Upstream(
        val queryRunner: GrpcEndpoint,
        val translator: GrpcEndpoint,
        val validator: GrpcEndpoint,
        val metadata: GrpcEndpoint,
    )

    data class Limits(
        val rowLimitDefault: Int,
        val rowLimitMax: Int,
        val requestTimeoutSeconds: Long,
        val maxMessageBytes: Int,
    )

    data class Security(
        val requireIdentity: Boolean,
    )

    companion object {
        fun load(config: Config = ConfigFactory.load()): QueryMcpConfig {
            val root = config.getConfig("theseus-mcp")
            return QueryMcpConfig(
                serverPort = config.getString("server.port").toInt(),
                mcpTransport = root.getString("mcp.transport"),
                mcpPath = root.getString("mcp.path"),
                upstream =
                    Upstream(
                        queryRunner = endpointOf(root.getConfig("upstream.theseus")),
                        translator = endpointOf(root.getConfig("upstream.proteus")),
                        validator = endpointOf(root.getConfig("upstream.argos")),
                        metadata = endpointOf(root.getConfig("upstream.ariadne")),
                    ),
                limits =
                    Limits(
                        rowLimitDefault = root.getInt("limits.row-limit-default"),
                        rowLimitMax = root.getInt("limits.row-limit-max"),
                        requestTimeoutSeconds = root.getLong("limits.request-timeout-seconds"),
                        maxMessageBytes = root.getInt("limits.max-message-bytes"),
                    ),
                security =
                    Security(
                        requireIdentity = root.getBoolean("security.require-identity"),
                    ),
                toolTimeoutsMs =
                    root
                        .getConfig("tool-timeouts")
                        .entrySet()
                        .associate {
                            it.key to
                                it.value
                                    .unwrapped()
                                    .toString()
                                    .toLong()
                        },
                metadataDecorationCacheTtlSeconds =
                    if (root.hasPath("upstream.ariadne.decoration-cache-ttl-seconds")) {
                        root.getLong("upstream.ariadne.decoration-cache-ttl-seconds")
                    } else {
                        30L
                    },
            )
        }

        private fun endpointOf(c: Config): GrpcEndpoint =
            GrpcEndpoint(
                host = c.getString("host"),
                port = c.getString("port").toInt(),
                deadlineSeconds = c.getLong("deadline-seconds"),
            )
    }
}
