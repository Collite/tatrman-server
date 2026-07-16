// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo

import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundingContext
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tatrman.geo.grpc.GeoGroundingService
import org.tatrman.geo.resolve.Boundary
import org.tatrman.geo.resolve.PlaceCandidate
import org.tatrman.geo.resolve.PlaceResolution
import org.tatrman.geo.resolve.PlaceResolver
import org.tatrman.geo.resolve.ResolvedPlace
import org.tatrman.text.Normalization

/**
 * A9.1 golden corpus — one parameterized case per row of `corpus/geo/cases.json`, run through the
 * full [GeoGroundingService.ground] pipeline (parser → resolver → recipe) over the in-memory POI
 * [FakeMetadataClient] and a deterministic fixture [CorpusResolver] (no live Nominatim). Expectations
 * are property-based (status / application / source / sql_preview fragments) rather than
 * full-GroundingResult-JSON equality.
 *
 * Every OK case is RULES-path (no LLM fallback in the harness) → satisfies the A9.7 ≥90%-rules DoD.
 */
class GeoCorpusSpec :
    StringSpec({
        loadCorpus().forEach { case ->
            "corpus/${case.name}: \"${case.span}\"" {
                val service =
                    GeoGroundingService(
                        FakeMetadataClient.poi(case.pkg),
                        placeResolver = CorpusResolver,
                        llmFallback = null,
                    )
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

private fun request(case: CorpusCase): GroundRequest =
    GroundRequest
        .newBuilder()
        .setSpanText(case.span)
        .setKind(EntityKind.LOCATION)
        .setPackage(case.pkg)
        .setContext(GroundingContext.newBuilder().setHerePlaceRef(case.here))
        .build()

/**
 * Deterministic fixture resolver: Brno / Praha (with boundaries, incl. the common cs declined forms)
 * resolve; "Újezd*" is ambiguous; everything else is unknown. Keeps the corpus offline + stable.
 */
private object CorpusResolver : PlaceResolver {
    private val brno =
        ResolvedPlace(
            "Brno",
            49.1951,
            16.6068,
            Boundary("POLYGON ((16.5 49.1, 16.7 49.1, 16.7 49.3, 16.5 49.3, 16.5 49.1))", 49.1, 16.5, 49.3, 16.7),
        )
    private val praha =
        ResolvedPlace(
            "Praha",
            50.0755,
            14.4378,
            Boundary("POLYGON ((14.2 49.9, 14.7 49.9, 14.7 50.2, 14.2 50.2, 14.2 49.9))", 49.9, 14.2, 50.2, 14.7),
        )
    private val known =
        mapOf(
            "brno" to brno,
            "brna" to brno,
            "brne" to brno,
            "praha" to praha,
            "prahy" to praha,
            "praze" to praha,
        )

    override suspend fun resolve(
        name: String,
        pkg: String,
    ): PlaceResolution {
        val key = fold(name)
        if (key.startsWith("ujezd")) {
            return PlaceResolution.Ambiguous(
                listOf(
                    PlaceCandidate("cz-1", "Újezd u Brna", 49.04, 16.77),
                    PlaceCandidate("cz-2", "Újezd nad Lesy", 50.08, 14.66),
                ),
            )
        }
        return known[key]?.let { PlaceResolution.Found(it) } ?: PlaceResolution.Unknown
    }

    // S-2 fold via the shared spec (RG-P6.S2.T4 — no private fold in tests either).
    private fun fold(s: String): String = Normalization.fold(s.trim())
}

private fun loadCorpus(): List<CorpusCase> {
    val json = object {}.javaClass.getResource("/corpus/geo/cases.json")!!.readText()
    return Json { ignoreUnknownKeys = true }.decodeFromString(json)
}

@Serializable
private data class CorpusCase(
    val name: String,
    val span: String,
    val here: String = "",
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
