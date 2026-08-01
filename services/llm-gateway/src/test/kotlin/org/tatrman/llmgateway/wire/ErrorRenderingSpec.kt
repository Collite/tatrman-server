// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.wire

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * LG-P1·S2·T1 — every [GatewayError] renders to the exact contracts §1.7 status/code/headers, and the
 * OpenAI error envelope is well-formed (SDK-parseable). Data-flow assertions on the rendered values.
 */
class ErrorRenderingSpec :
    StringSpec({

        "Validation → 400 invalid_request_error, message carried" {
            val r = GatewayError.Validation("bad 'model'").render()
            r.status shouldBe HttpStatusCode.BadRequest
            r.type shouldBe "invalid_request_error"
            r.message shouldBe "bad 'model'"
        }

        "ContextLength → 400 context_length_exceeded" {
            GatewayError.ContextLength().render().let {
                it.status shouldBe HttpStatusCode.BadRequest
                it.code shouldBe "context_length_exceeded"
            }
        }

        "ContentFilter → 400 content_filter" {
            GatewayError.ContentFilter().render().code shouldBe "content_filter"
        }

        "Auth → 401 invalid_api_key" {
            GatewayError.Auth(401).render().let {
                it.status shouldBe HttpStatusCode.Unauthorized
                it.code shouldBe "invalid_api_key"
            }
        }

        "RateLimit with retry-after → 429 rate_limit_exceeded + Retry-After header (seconds)" {
            GatewayError.RateLimit(retryAfterMs = 2000).render().let {
                it.status shouldBe HttpStatusCode.TooManyRequests
                it.code shouldBe "rate_limit_exceeded"
                it.headers["Retry-After"] shouldBe "2"
            }
        }

        "RateLimit without retry-after → no Retry-After header" {
            GatewayError
                .RateLimit(retryAfterMs = null)
                .render()
                .headers["Retry-After"]
                .shouldBeNull()
        }

        "BudgetExceeded → 429 insufficient_quota + x-gateway-reason header" {
            GatewayError.BudgetExceeded().render().let {
                it.status shouldBe HttpStatusCode.TooManyRequests
                it.code shouldBe "insufficient_quota"
                it.headers["x-gateway-reason"] shouldBe "budget_exceeded"
            }
        }

        "Provider5xx → 502 upstream_error, original status surfaced in the message" {
            GatewayError.Provider5xx(529).render().let {
                it.status shouldBe HttpStatusCode.BadGateway
                it.code shouldBe "upstream_error"
                it.message.contains("529") shouldBe true
            }
        }

        "Timeout / Network → 502 upstream_error" {
            GatewayError.Timeout().render().status shouldBe HttpStatusCode.BadGateway
            GatewayError.Network().render().status shouldBe HttpStatusCode.BadGateway
        }

        "Internal → 500 internal_error" {
            GatewayError.Internal(null).render().let {
                it.status shouldBe HttpStatusCode.InternalServerError
                it.code shouldBe "internal_error"
            }
        }

        "the OpenAI error envelope has message/type/code/param" {
            val err = openAiErrorBody("invalid_request_error", "invalid_api_key", "nope")["error"]!!.jsonObject
            err["message"]!!.jsonPrimitive.content shouldBe "nope"
            err["type"]!!.jsonPrimitive.content shouldBe "invalid_request_error"
            err["code"]!!.jsonPrimitive.content shouldBe "invalid_api_key"
            err.containsKey("param") shouldBe true // present as JSON null
        }
    })
