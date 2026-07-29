// SPDX-License-Identifier: Apache-2.0
package shared.ktor

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Proves `responseWriteTimeoutSeconds` actually reaches the Netty engine.
 *
 * `KtorServerConfigSpec` asserts on a data class, which would stay green even if the `configure`
 * block in [KtorServerBootstrap] were deleted outright. This spec starts a **real Netty server on
 * a real socket** and reads the property's effect through a real client.
 *
 * Two things about the handler shape are load-bearing, both measured in ST-P2·S1·T1:
 *
 *  1. It must open a **streaming** response and *then* stall. The write timeout arms only for a
 *     write that is already pending; a handler that thinks for 5s and only then calls
 *     `respondText` is never reaped at all, so that shape would produce a green test that proves
 *     nothing.
 *  2. Case A (the short timeout) is what makes Case B meaningful. A single passing case is
 *     indistinguishable from the property having no effect.
 *
 * See `project/server/features/stream-timeouts/contracts.md` §1-§2.
 */
class KtorServerBootstrapNettySpec :
    StringSpec({

        val stallMs = 5_000L

        // The SSE shape, reduced to its essentials: commit a chunked response, then go quiet for
        // longer than the timeout under test.
        val stallingModule: Application.() -> Unit = {
            routing {
                get("/stall") {
                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                        delay(stallMs)
                        write(": late\n\n")
                        flush()
                    }
                }
            }
        }

        fun serverWith(timeoutSeconds: Int): EmbeddedServer<*, *> =
            KtorServerBootstrap.createServer(
                KtorServerConfig(
                    serviceName = "bootstrap-netty-spec",
                    serverPort = 0, // ephemeral — resolved after start
                    engine = KtorEngine.NETTY,
                    responseWriteTimeoutSeconds = timeoutSeconds,
                ),
                stallingModule,
            )

        suspend fun callStall(server: EmbeddedServer<*, *>): Pair<Result<String>, Long> {
            val port =
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            val client = HttpClient(CIO)
            val started = System.nanoTime()
            val outcome = runCatching { client.get("http://127.0.0.1:$port/stall").bodyAsText() }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            client.close()
            return outcome to elapsedMs
        }

        "Case A — a short timeout reaps the stalled stream, so the property is demonstrably live"
            .config(timeout = 60.seconds) {
                val server = serverWith(timeoutSeconds = 2)
                server.start(wait = false)
                try {
                    val (outcome, elapsedMs) = callStall(server)

                    outcome.isFailure shouldBe true
                    // Reaped at the timeout, not merely slow: it must die well before the handler
                    // would have finished. Without this bound, a client-side failure at 5s would
                    // look identical to the engine timeout firing.
                    (elapsedMs < stallMs) shouldBe true
                } finally {
                    server.stop(0, 0)
                }
            }

        "Case B — a generous timeout lets the same stalled stream complete"
            .config(timeout = 60.seconds) {
                val server = serverWith(timeoutSeconds = 30)
                server.start(wait = false)
                try {
                    val (outcome, elapsedMs) = callStall(server)

                    outcome.isSuccess shouldBe true
                    outcome.getOrThrow().trim() shouldBe ": late"
                    // It really did stall — otherwise Case A's failure would not be attributable
                    // to the timeout.
                    (elapsedMs >= stallMs) shouldBe true
                } finally {
                    server.stop(0, 0)
                }
            }
    })
