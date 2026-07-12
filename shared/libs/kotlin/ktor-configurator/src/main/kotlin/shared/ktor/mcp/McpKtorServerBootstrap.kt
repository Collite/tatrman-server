// SPDX-License-Identifier: Apache-2.0
package shared.ktor.mcp

import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.opentelemetry.api.OpenTelemetry

object McpKtorServerBootstrap {
    fun createMcpServer(
        config: McpKtorConfig,
        openTelemetry: OpenTelemetry,
        mcpServerFactory: () -> Server,
    ): EmbeddedServer<*, *> {
        val appConfig =
            serverConfig {
                module {
                    installMcpKtorBase(config, openTelemetry)
                    mcpStreamableHttp { mcpServerFactory() }
                }
            }

        return embeddedServer(
            factory = CIO,
            rootConfig = appConfig,
            configure = {
                connectionIdleTimeoutSeconds = config.connectionIdleTimeoutSeconds.toInt()
                connectors.add(
                    EngineConnectorBuilder().apply {
                        port = config.serverPort
                        host = "0.0.0.0"
                    },
                )
            },
        )
    }
}
