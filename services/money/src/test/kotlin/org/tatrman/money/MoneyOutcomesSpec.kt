// SPDX-License-Identifier: Apache-2.0
package org.tatrman.money

import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.GroundingResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.money.client.GatewayResponseFormat
import org.tatrman.money.client.LlmGatewayClient
import org.tatrman.money.grpc.MoneyGroundingService

/**
 * A10 outcomes over the full [MoneyGroundingService.ground] pipeline (recognizer → discovery →
 * recipe), plus the llm-gateway fallback. In-memory [FakeMetadataClient] fixtures; default CZK.
 */
class MoneyOutcomesSpec :
    StringSpec({
        fun svc(
            client: FakeMetadataClient = FakeMetadataClient.domestic("cnc"),
            fallback: LlmGatewayClient? = null,
        ) = MoneyGroundingService(client, llmFallback = fallback, defaultCurrency = "CZK")

        fun request(
            span: String,
            answerId: String = "",
            locale: String = "cs-CZ",
        ): GroundRequest =
            GroundRequest
                .newBuilder()
                .setSpanText(span)
                .setKind(EntityKind.MONEY)
                .setPackage("cnc")
                .setContext(GroundingContext.newBuilder().setLocale(locale).setDefaultCurrency("CZK"))
                .apply { if (answerId.isNotEmpty()) clarificationAnswerId = answerId }
                .build()

        "domestic 'faktury nad 100 000' → OK FilterRecipe on amount_domestic" {
            val r = svc().ground(request("faktury nad 100 000"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.result.sqlPreview shouldContain "t.\"amount_dom\" > {amt}"
        }

        "tolerance 'kolem 100k' → OK with a normalized band" {
            val r = svc().ground(request("kolem 100k"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.normalized.money.lowerBound shouldBe "90000"
            r.result.normalized.money.upperBound shouldBe "110000"
        }

        "empty locale falls back to cs separators — a Czech decimal is not 100x-inflated" {
            val r = svc().ground(request("faktury nad 100,50", locale = ""))
            r.status shouldBe GroundResponse.Status.OK
            r.result.normalized.money.amount shouldBe "100.5"
        }

        "current-rate FX with no reference date → UNGROUNDABLE (no silent rate fan-out)" {
            val r =
                svc(FakeMetadataClient.withFxTable("cnc"))
                    .ground(request("over 5000 EUR at today's rate", locale = "en-US"))
            r.status shouldBe GroundResponse.Status.UNGROUNDABLE
        }

        "foreign 'over 5 000 EUR' + fx table → OK JoinRecipe" {
            val r = svc(FakeMetadataClient.withFxTable("cnc")).ground(request("over 5 000 EUR", locale = "en-US"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.applicationCase shouldBe GroundingResult.ApplicationCase.JOIN
        }

        "foreign 'over 5 000 EUR' with no fx table + no currency_code → UNGROUNDABLE" {
            val r = svc(FakeMetadataClient.amountOnly("cnc")).ground(request("over 5 000 EUR", locale = "en-US"))
            r.status shouldBe GroundResponse.Status.UNGROUNDABLE
        }

        "two amount columns → AWAITING_CLARIFICATION naming the columns" {
            val r = svc(FakeMetadataClient.ambiguousAmounts("cnc")).ground(request("nad 100"))
            r.status shouldBe GroundResponse.Status.AWAITING_CLARIFICATION
            r.optionsList.map { it.id } shouldBe listOf("net_amount", "gross_amount")
        }

        "echoing a clarification_answer_id resolves the column → OK" {
            val r =
                svc(FakeMetadataClient.ambiguousAmounts("cnc"))
                    .ground(request("nad 100", answerId = "gross_amount"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.sqlPreview shouldContain "t.\"gross_amount\""
        }

        // ----- llm-gateway fallback (A10.6) -----

        "an unrecognized span with no fallback client → UNGROUNDABLE" {
            svc().ground(request("some pricey stuff")).status shouldBe GroundResponse.Status.UNGROUNDABLE
        }

        "an unrecognized span with a fallback client → OK, source = LLM" {
            val gateway = FakeLlmGateway(VALID_LLM_RESULT)
            val r = svc(fallback = gateway).ground(request("roughly a fortune's worth of orders"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.source shouldBe GroundingResult.Source.LLM
            gateway.lastUserPrompt!!.contains("fortune") shouldBe true
        }

        "a fallback client returning invalid JSON → UNGROUNDABLE (not a crash)" {
            svc(fallback = FakeLlmGateway("not json"))
                .ground(request("some pricey stuff"))
                .status shouldBe GroundResponse.Status.UNGROUNDABLE
        }

        "D-T4: a rules-hit with a gateway present stays RULES and never calls the LLM" {
            val gateway = FakeLlmGateway(VALID_LLM_RESULT)
            val r = svc(fallback = gateway).ground(request("faktury nad 100 000"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.source shouldBe GroundingResult.Source.RULES
            gateway.lastUserPrompt.shouldBeNull() // fallback fires only on a rules-miss / low-confidence
        }
    })

private class FakeLlmGateway(
    private val response: String,
) : LlmGatewayClient {
    var lastUserPrompt: String? = null

    override suspend fun chat(
        model: String,
        system: String,
        user: String,
        responseFormat: GatewayResponseFormat?,
    ): String {
        lastUserPrompt = user
        return response
    }

    override fun close() {}
}

// Minimal structurally-valid GroundingResult (proto JSON): normalized.money + a filter + sql_preview.
private const val VALID_LLM_RESULT = """
{
  "normalized": { "money": { "amount": "100000", "currency": "CZK" } },
  "filter": {},
  "sqlPreview": "t.\"amount\" >= {amt}",
  "confidence": 0.5,
  "explanation": "LLM-grounded to ~100k CZK."
}
"""
