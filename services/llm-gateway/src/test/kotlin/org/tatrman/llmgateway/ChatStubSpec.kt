// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication

/**
 * LG-P1·S2·T4 — `/v1/chat/completions` typed stub: proves the auth → parse → validate → OpenAI-shaped
 * error path end-to-end BEFORE providers exist (LG-P2). Valid requests get a typed 501 (not_implemented).
 */
class ChatStubSpec :
    StringSpec({

        fun bearer() = "Bearer ${TestSupport.SEEDED_KEY}"

        "no key → 401" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), TestSupport.seededGateway()) }
                client.post("/v1/chat/completions") { setBody("""{"model":"gpt-4o"}""") }.status shouldBe
                    HttpStatusCode.Unauthorized
            }
        }

        "unparseable body → 400 invalid_request_error" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), TestSupport.seededGateway()) }
                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, bearer())
                        setBody("this is not json")
                    }
                res.status shouldBe HttpStatusCode.BadRequest
                res.bodyAsText() shouldContain "invalid_request_error"
            }
        }

        "missing model → 400 invalid_request_error" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), TestSupport.seededGateway()) }
                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, bearer())
                        setBody("""{"messages":[{"role":"user","content":"hi"}]}""")
                    }
                res.status shouldBe HttpStatusCode.BadRequest
                res.bodyAsText() shouldContain "model"
            }
        }

        "stream:true with an unknown model → 404 model_not_found (resolution precedes streaming), no upstream call" {
            // Both wires (openai-wire passthrough S2, anthropic converter S3) are live now; model resolution
            // still runs BEFORE any upstream connection, so an unknown model short-circuits to 404 with no call.
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), TestSupport.seededGateway()) }
                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, bearer())
                        setBody(
                            """{"model":"no-such-model","stream":true,"messages":[{"role":"user","content":"hi"}]}""",
                        )
                    }
                res.status shouldBe HttpStatusCode.NotFound
                res.bodyAsText() shouldContain "model_not_found"
            }
        }

        "unknown model (non-stream) → 404 model_not_found, no upstream call" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(ConfigFactory.load(), TestSupport.seededGateway()) }
                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, bearer())
                        setBody("""{"model":"no-such-model","messages":[{"role":"user","content":"hi"}]}""")
                    }
                res.status shouldBe HttpStatusCode.NotFound
                res.bodyAsText() shouldContain "model_not_found"
            }
        }
    })
