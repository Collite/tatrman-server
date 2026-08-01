// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.wire

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * LG-P1·S2·T3 — the OpenAI-wire error converter maps upstream (status, body) → [GatewayError] with the
 * right retry/chain semantics, and — the **named FI-6 regression** — carries the ORIGINAL upstream
 * status through (the ai-gateway experiment collapsed every 5xx to 500, breaking retry decisions).
 */
class ErrorConverterSpec :
    StringSpec({

        fun body(json: String) = Json.parseToJsonElement(json).jsonObject

        "carries the ORIGINAL status through (529 stays 529, not 500) — FI-6 regression" {
            val e = OpenAiWireErrorConverter.convert(529, body("""{"error":{"type":"overloaded_error"}}"""))
            e.shouldBeInstanceOf<GatewayError.Provider5xx>()
            e.status shouldBe 529
            e.retryable shouldBe true
            e.chainEligible shouldBe true
        }

        "500 → Provider5xx(500)" {
            (OpenAiWireErrorConverter.convert(500, null) as GatewayError.Provider5xx).status shouldBe 500
        }

        "401 and 403 → Auth carrying the original status" {
            (OpenAiWireErrorConverter.convert(401, null) as GatewayError.Auth).status shouldBe 401
            (OpenAiWireErrorConverter.convert(403, null) as GatewayError.Auth).status shouldBe 403
        }

        "408 → Timeout (retryable)" {
            OpenAiWireErrorConverter.convert(408, null).shouldBeInstanceOf<GatewayError.Timeout>()
        }

        "429 → RateLimit (retryable, chain-eligible)" {
            val e = OpenAiWireErrorConverter.convert(429, null)
            e.shouldBeInstanceOf<GatewayError.RateLimit>()
            e.retryable shouldBe true
        }

        "400 + context_length code → ContextLength (non-retryable BUT chain-eligible — LG-D2)" {
            val e = OpenAiWireErrorConverter.convert(400, body("""{"error":{"code":"context_length_exceeded"}}"""))
            e.shouldBeInstanceOf<GatewayError.ContextLength>()
            e.retryable shouldBe false
            e.chainEligible shouldBe true
        }

        "400 + content_filter → ContentFilter" {
            OpenAiWireErrorConverter
                .convert(400, body("""{"error":{"type":"content_filter"}}"""))
                .shouldBeInstanceOf<GatewayError.ContentFilter>()
        }

        "400 plain → Validation (non-retryable)" {
            val e = OpenAiWireErrorConverter.convert(400, body("""{"error":{"message":"bad param"}}"""))
            e.shouldBeInstanceOf<GatewayError.Validation>()
            e.retryable shouldBe false
        }

        "Retry-After parses seconds and http-date; garbage/absent → null" {
            parseRetryAfterMs("2", nowEpochMs = 0) shouldBe 2000L
            val fromDate = parseRetryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT", nowEpochMs = 0)
            (fromDate != null && fromDate > 0) shouldBe true
            parseRetryAfterMs("soon", nowEpochMs = 0) shouldBe null
            parseRetryAfterMs(null, nowEpochMs = 0) shouldBe null
        }
    })
