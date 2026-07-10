package org.tatrman.health.checker

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf

class PrometheusHealthCheckerTest :
    StringSpec({

        "PromQL braces/quotes are percent-encoded so the request URL is legal" {
            // Regression: the old code only replaced `"` and left `{`/`}` raw, so the Apache
            // client threw `Illegal character in query at index 44` before sending anything.
            var requested = ""
            val engine =
                MockEngine { request ->
                    requested = request.url.toString()
                    respond(
                        content = """{"status":"success","data":{"result":[{"value":[0,"1"]}]}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val checker =
                PrometheusHealthChecker(
                    technology = "keycloak",
                    prometheusUrl = "http://prometheus:9090",
                    job = "keycloak",
                )
            checker.replaceClient(HttpClient(engine))

            val result = checker.check()

            result.status shouldBe "healthy"
            requested shouldNotContain "{"
            requested shouldNotContain "}"
            requested shouldContain "up%7Bjob%3D%22keycloak%22%7D"
        }

        "metric value other than 1 is reported unhealthy" {
            val engine =
                MockEngine {
                    respond(
                        content = """{"status":"success","data":{"result":[{"value":[0,"0"]}]}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val checker =
                PrometheusHealthChecker(
                    technology = "postgres",
                    prometheusUrl = "http://prometheus:9090",
                    job = "postgres",
                )
            checker.replaceClient(HttpClient(engine))

            checker.check().status shouldBe "unhealthy"
        }
    })
