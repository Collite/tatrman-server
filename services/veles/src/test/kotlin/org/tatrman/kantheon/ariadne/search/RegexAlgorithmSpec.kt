package org.tatrman.kantheon.ariadne.search

import org.tatrman.ariadne.v1.SearchRequest
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.parseSchemaCode
import org.tatrman.kantheon.ariadne.graph.ModelGraph
import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.model.ModelVersion
import org.tatrman.kantheon.ariadne.model.Query
import org.tatrman.kantheon.ariadne.model.SearchHints
import org.tatrman.kantheon.ariadne.registry.RegistrySnapshot
import org.tatrman.kantheon.ariadne.search.regex.RegexAlgorithm
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class RegexAlgorithmSpec :
    StringSpec({

        "extracts {customer_id} placeholder from a query" {
            val algo = RegexAlgorithm()
            val q =
                query(
                    "ordersForCustomer",
                    search = SearchHints(patterns = listOf("objednávky zákazníka {customer_id}")),
                )
            val out = algo.rebuild(snap(q), "cs")
            val hits = algo.search(req("objednávky zákazníka 42", includeParams = true), out.index)
            hits.size shouldBe 1
            hits[0].matchedField shouldBe "pattern"
            hits[0].extractedParameters["customer_id"] shouldBe "42"
            hits[0].score shouldBe 1.0f
            hits[0].patternIndex shouldBe 0
        }

        "greedy-tail captures multi-token tail value" {
            val algo = RegexAlgorithm()
            val q =
                query(
                    "ordersForCustomerByName",
                    search = SearchHints(patterns = listOf("objednávky zákazníka {name}")),
                )
            val out = algo.rebuild(snap(q), "cs")
            val hits = algo.search(req("objednávky zákazníka kaufland a.s.", includeParams = true), out.index)
            hits.size shouldBe 1
            // Diacritic-fold normalises "kaufland a.s." → "kaufland a.s." (no diacritics anyway), trim runs.
            hits[0].extractedParameters["name"] shouldBe "kaufland a.s."
        }

        "Czech parameter name in capture group is preserved as the extraction key" {
            val algo = RegexAlgorithm()
            val q =
                query(
                    "kodLookup",
                    search = SearchHints(patterns = listOf("kód uživatele {kód_uživatele}")),
                )
            val out = algo.rebuild(snap(q), "cs")
            val hits = algo.search(req("kód uživatele 999", includeParams = true), out.index)
            hits.size shouldBe 1
            hits[0].extractedParameters.keys shouldBe setOf("kód_uživatele")
            hits[0].extractedParameters["kód_uživatele"] shouldBe "999"
        }

        "uncompilable pattern is recorded as a CompileError without blocking other patterns" {
            val algo = RegexAlgorithm()
            // Hits the duplicate-group-name path: both placeholders sanitise to
            // the same Java group name, which Pattern.compile rejects.
            val q =
                query(
                    "broken",
                    search =
                        SearchHints(
                            patterns =
                                listOf(
                                    "good {name}",
                                    "first {x} second {x}",
                                ),
                        ),
                )
            val out = algo.rebuild(snap(q), "cs")
            out.compileErrors.size shouldBe 1
            out.compileErrors[0].field shouldBe "patterns[1]"
            // Valid pattern still matchable.
            algo.search(req("good thing", includeParams = true), out.index).size shouldBe 1
        }

        "substring match scores 0.85" {
            val algo = RegexAlgorithm()
            val q =
                query(
                    "Q",
                    search = SearchHints(patterns = listOf("zákazník {x}")),
                )
            val out = algo.rebuild(snap(q), "cs")
            val hits = algo.search(req("hej zákazník 42 dnes", includeParams = false), out.index)
            hits.size shouldBe 1
            hits[0].score shouldBe 0.85f
        }

        "include_extracted_parameters=false yields an empty extraction map" {
            val algo = RegexAlgorithm()
            val q =
                query(
                    "Q",
                    search = SearchHints(patterns = listOf("zákazník {x}")),
                )
            val out = algo.rebuild(snap(q), "cs")
            val hits = algo.search(req("zákazník 42", includeParams = false), out.index)
            hits.size shouldBe 1
            hits[0].extractedParameters.isEmpty() shouldBe true
        }

        "sanitizeGroupName strips underscores and transliterates Czech accents" {
            val sanitized = RegexAlgorithm.sanitizeGroupName("kód_uživatele")
            sanitized shouldBe "koduzivatele"
        }
    })

private fun req(
    query: String,
    includeParams: Boolean,
): SearchRequest =
    SearchRequest
        .newBuilder()
        .setQuery(query)
        .setLanguage("cs")
        .setIncludeExtractedParameters(includeParams)
        .build()

private fun query(
    name: String,
    search: SearchHints = SearchHints(),
): Query =
    Query(
        internalId = "id-$name",
        qname = qn("query", "query", name),
        sourceLanguage = "SQL",
        sourceText = "select 1",
        search = search,
    )

private fun snap(vararg q: Query): RegistrySnapshot {
    val model =
        Model(
            descriptor = ModelDescriptor("test-id", "test"),
            version = ModelVersion("0", Instant.EPOCH),
            schemas = emptyMap(),
            mappings = emptyList(),
            queries = q.associateBy { it.qname },
        )
    return RegistrySnapshot(model, ModelGraph.build(model), Instant.EPOCH, emptyList())
}

private fun qn(
    schema: String,
    namespace: String,
    name: String,
): QualifiedName =
    QualifiedName
        .newBuilder()
        .setSchemaCode(parseSchemaCode(schema) ?: SchemaCode.SCHEMA_CODE_UNSPECIFIED)
        .setNamespace(namespace)
        .setName(name)
        .build()
