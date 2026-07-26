package org.tatrman.charon

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/**
 * Regression guard for the ContentNegotiation-or-406 trap (EXAMPLES.md §2a).
 *
 * The Ktor `module()` responds to `/health` and `/ready` with
 * `call.respond(buildJsonObject { … })`. Without ContentNegotiation those
 * responses have no negotiated representation and Ktor answers **HTTP 406**,
 * which the kubelet liveness probe reads as a failure → CrashLoopBackOff.
 * CH-D4 aligned charon onto the shared `installKtorServerBase` (the 9-sibling
 * pattern), which installs ContentNegotiation(json); this spec fails with 406 if
 * that base install is ever dropped. Bodies are whitespace-insensitive here — the
 * house base pretty-prints JSON, so asserts strip whitespace before matching.
 */
class HealthRoutesSpec :
    StringSpec({

        fun meter() = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        "/health serves 200 JSON (not 406)" {
            testApplication {
                application { module(meter()) }
                val res = client.get("/health")
                res.status shouldBe HttpStatusCode.OK
                res.bodyAsText().replace(Regex("\\s"), "") shouldContain "\"status\":\"UP\""
            }
        }

        "/ready serves 200 JSON (not 406)" {
            testApplication {
                application { module(meter()) }
                val res = client.get("/ready")
                res.status shouldBe HttpStatusCode.OK
                res.bodyAsText().replace(Regex("\\s"), "") shouldContain "\"status\":\"UP\""
            }
        }

        "/status serves 200 JSON (not 406)" {
            testApplication {
                application { module(meter()) }
                val res = client.get("/status")
                res.status shouldBe HttpStatusCode.OK
                res.bodyAsText().replace(Regex("\\s"), "") shouldContain "\"service\":\"charon\""
            }
        }
    })
