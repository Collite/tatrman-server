// SPDX-License-Identifier: Apache-2.0
package shared.ktor

import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.*

object KtorServerBootstrap {
    fun createServer(
        config: KtorServerConfig,
        module: Application.() -> Unit,
    ): EmbeddedServer<*, *> =
        when (config.engine) {
            // Unchanged. CIO has no responseWriteTimeoutSeconds; its idle handling is owned by
            // McpKtorConfig.connectionIdleTimeoutSeconds and conflating the two is out of scope.
            KtorEngine.CIO -> embeddedServer(CIO, port = config.serverPort, host = "0.0.0.0") { module() }
            // The port moves INSIDE `configure` because Ktor 3.2.3 has no embeddedServer overload
            // taking both port/host and configure — the port/host overloads build their connector
            // internally. Same bound socket, same module; see contracts.md §2.
            //
            // Without this block Ktor's default of 10s stands, which silently caps
            // time-to-first-byte for every streaming response the service serves.
            KtorEngine.NETTY ->
                embeddedServer(
                    Netty,
                    configure = {
                        connector {
                            port = config.serverPort
                            host = "0.0.0.0"
                        }
                        responseWriteTimeoutSeconds = config.responseWriteTimeoutSeconds
                    },
                ) { module() }
        }
}
