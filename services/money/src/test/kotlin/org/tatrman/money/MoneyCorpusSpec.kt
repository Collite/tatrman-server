package org.tatrman.money

import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundingContext
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tatrman.money.grpc.MoneyGroundingService

/**
 * A10.1 golden corpus — one parameterized case per row of `corpus/money/cases.json`, run through the
 * full [MoneyGroundingService.ground] pipeline (recognizer → discovery → recipe) over the in-memory
 * [FakeMetadataClient] fixture named by each case (`model`). Property-based expectations (status /
 * application / source / sql_preview fragments). Every OK case is RULES-path (no LLM in the harness)
 * → satisfies the A10.6 ≥90%-rules DoD.
 */
class MoneyCorpusSpec :
    StringSpec({
        loadCorpus().forEach { case ->
            "corpus/${case.name}: \"${case.span}\"" {
                val service = MoneyGroundingService(clientFor(case.model, case.pkg), llmFallback = null)
                val response = service.ground(request(case))

                response.status.name shouldBe case.expect.status
                if (case.expect.status == "OK") {
                    val result = response.result
                    case.expect.application?.let { result.applicationCase.name shouldBe it }
                    case.expect.source?.let { result.source.name shouldBe it }
                    case.expect.sqlContains.forEach { result.sqlPreview shouldContain it }
                }
            }
        }
    })

private fun clientFor(
    model: String,
    pkg: String,
): FakeMetadataClient =
    when (model) {
        "fx" -> FakeMetadataClient.withFxTable(pkg)
        "native" -> FakeMetadataClient.withCurrencyCode(pkg)
        "amountOnly" -> FakeMetadataClient.amountOnly(pkg)
        "ambiguous" -> FakeMetadataClient.ambiguousAmounts(pkg)
        else -> FakeMetadataClient.domestic(pkg)
    }

private fun request(case: CorpusCase): GroundRequest =
    GroundRequest
        .newBuilder()
        .setSpanText(case.span)
        .setKind(EntityKind.MONEY)
        .setPackage(case.pkg)
        .setContext(
            GroundingContext
                .newBuilder()
                .setLocale(case.locale)
                .setDefaultCurrency(case.defaultCurrency)
                .setReferenceDatetime(case.referenceDatetime),
        ).build()

private fun loadCorpus(): List<CorpusCase> {
    val json = object {}.javaClass.getResource("/corpus/money/cases.json")!!.readText()
    return Json { ignoreUnknownKeys = true }.decodeFromString(json)
}

@Serializable
private data class CorpusCase(
    val name: String,
    val span: String,
    val model: String = "domestic",
    val locale: String = "cs-CZ",
    val defaultCurrency: String = "CZK",
    val referenceDatetime: String = "",
    val pkg: String = "cnc",
    val expect: Expect,
)

@Serializable
private data class Expect(
    val status: String,
    val application: String? = null,
    val source: String? = null,
    val sqlContains: List<String> = emptyList(),
)
