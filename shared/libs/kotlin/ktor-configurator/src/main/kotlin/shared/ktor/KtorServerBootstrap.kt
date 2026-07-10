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
            KtorEngine.CIO -> embeddedServer(CIO, port = config.serverPort, host = "0.0.0.0") { module() }
            KtorEngine.NETTY -> embeddedServer(Netty, port = config.serverPort, host = "0.0.0.0") { module() }
        }
}
