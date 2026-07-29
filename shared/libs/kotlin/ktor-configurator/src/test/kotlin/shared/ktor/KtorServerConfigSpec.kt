// SPDX-License-Identifier: Apache-2.0
package shared.ktor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * `responseWriteTimeoutSeconds` — the value that took the Hartland demo down on 2026-07-29 by
 * being an invisible Ktor default rather than a stated decision.
 *
 * The validation is the point of this spec. Netty does **not** reject a non-positive write
 * timeout: `WriteTimeoutHandler` clamps `<= 0` to "never schedule" and carries on silently, and
 * Ktor installs that handler unconditionally. So a typo'd `0` or `-1` would disable the reaper
 * for every Netty service on this bootstrap with no error anywhere — exactly the class of
 * failure this effort exists to stop.
 *
 * See `project/server/features/stream-timeouts/contracts.md` §1.
 */
class KtorServerConfigSpec :
    StringSpec({

        "the default is 180s — far above any legitimate inter-frame gap, still bounded" {
            KtorServerConfig(serviceName = "svc", serverPort = 7410).responseWriteTimeoutSeconds shouldBe 180
        }

        "a positive value is accepted" {
            KtorServerConfig(
                serviceName = "svc",
                serverPort = 7410,
                responseWriteTimeoutSeconds = 30,
            ).responseWriteTimeoutSeconds shouldBe 30
        }

        "0 is rejected — it silently DISABLES the timeout rather than shortening it" {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    KtorServerConfig(serviceName = "svc", serverPort = 7410, responseWriteTimeoutSeconds = 0)
                }
            ex.message!! shouldContain "responseWriteTimeoutSeconds"
        }

        "a negative value is rejected for the same reason" {
            shouldThrow<IllegalArgumentException> {
                KtorServerConfig(serviceName = "svc", serverPort = 7410, responseWriteTimeoutSeconds = -1)
            }
        }

        "every other field keeps its existing default — the new field is appended, not disruptive" {
            val config = KtorServerConfig(serviceName = "svc", serverPort = 7410)
            config.engine shouldBe KtorEngine.CIO
            config.telemetryEnabled shouldBe true
            config.forwardedHeaderEnabled shouldBe false
        }
    })
