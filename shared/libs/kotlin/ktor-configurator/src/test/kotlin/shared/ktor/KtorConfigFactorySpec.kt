// SPDX-License-Identifier: Apache-2.0
package shared.ktor

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * HOCON resolution for `responseWriteTimeoutSeconds`.
 *
 * Precedence is `server.response-write-timeout-s` → the legacy `ktor.deployment.*` section →
 * the built-in `180`, matching how `port` is already resolved. The env override
 * (`KTOR_RESPONSE_WRITE_TIMEOUT_S`) is applied by HOCON's `${?VAR}` in each service's
 * `application.conf`, not by reading `System.getenv` here — so it is not this spec's business.
 *
 * See `project/server/features/stream-timeouts/contracts.md` §3.
 */
class KtorConfigFactorySpec :
    StringSpec({

        // `telemetry.enabled` is read unconditionally by fromConfig, so every fixture needs it.
        fun config(body: String) = ConfigFactory.parseString("telemetry { enabled = false }\n$body")

        fun resolve(body: String) =
            KtorConfigFactory.fromConfig(
                config = config(body),
                defaultServiceName = "svc",
                defaultPort = 7410,
                engine = KtorEngine.NETTY,
            )

        "neither section present — the built-in 180 stands" {
            resolve("ktor { deployment { port = 7410 } }").responseWriteTimeoutSeconds shouldBe 180
        }

        "server.response-write-timeout-s is honoured" {
            resolve(
                """
                server { port = 7410, response-write-timeout-s = 45 }
                """.trimIndent(),
            ).responseWriteTimeoutSeconds shouldBe 45
        }

        "the legacy ktor.deployment section is honoured when server is absent" {
            resolve(
                """
                ktor { deployment { port = 7410, response-write-timeout-s = 90 } }
                """.trimIndent(),
            ).responseWriteTimeoutSeconds shouldBe 90
        }

        "server wins when both are present" {
            resolve(
                """
                server { port = 7410, response-write-timeout-s = 45 }
                ktor { deployment { port = 7410, response-write-timeout-s = 90 } }
                """.trimIndent(),
            ).responseWriteTimeoutSeconds shouldBe 45
        }

        // Guards the seam the estate actually uses: a service that sets nothing must still get a
        // value that is (a) valid and (b) far above the 10s cliff this whole effort is about.
        "a service that configures nothing is no longer exposed to Ktor's 10s default" {
            val resolved = resolve("server { port = 7410 }").responseWriteTimeoutSeconds
            (resolved > 10) shouldBe true
        }
    })
