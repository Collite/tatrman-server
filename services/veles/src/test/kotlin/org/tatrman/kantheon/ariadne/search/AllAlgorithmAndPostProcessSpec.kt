package org.tatrman.kantheon.ariadne.search

import org.tatrman.ariadne.v1.PageRequest
import org.tatrman.ariadne.v1.SearchRequest
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.parseSchemaCode
import org.tatrman.kantheon.ariadne.graph.ModelGraph
import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.model.ModelVersion
import org.tatrman.kantheon.ariadne.registry.RegistrySnapshot
import org.tatrman.kantheon.ariadne.search.all.AllAlgorithm
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class AllAlgorithmAndPostProcessSpec :
    StringSpec({

        "all merges hits per owner and keeps the highest-scoring algorithm" {
            // q1 hit by both algos; "regex" wins because it scores 1.0 > "keyword" 0.7.
            val q1 = qn("query", "query", "Q1")
            val q2 = qn("query", "query", "Q2")
            val keywordAlgo =
                StaticAlgorithm(
                    "keyword",
                    listOf(hit(q1, 0.7f, "keyword", "keyword"), hit(q2, 0.6f, "keyword", "keyword")),
                )
            val regexAlgo =
                StaticAlgorithm(
                    "regex",
                    listOf(hit(q1, 1.0f, "pattern", "regex")),
                )
            val registry = SearchAlgorithmRegistry(mapOf("keyword" to keywordAlgo, "regex" to regexAlgo))
            val holder = SearchIndexHolder(registry, listOf("cs"))
            holder.rebuild(emptySnapshot())
            val all = AllAlgorithm(registry, holder)

            val merged = all.search(req(""), SearchIndex.Empty)
            merged.map { it.ownerQname to it.algorithm } shouldBe
                listOf(q1 to "regex", q2 to "keyword")
            merged[0].score shouldBe 1.0f
            merged[1].score shouldBe 0.6f
        }

        "post-process applies threshold then page" {
            val q =
                listOf(
                    hit(qn("q", "q", "A"), 0.95f, "x"),
                    hit(qn("q", "q", "B"), 0.90f, "x"),
                    hit(qn("q", "q", "C"), 0.85f, "x"),
                    hit(qn("q", "q", "D"), 0.70f, "x"),
                    hit(qn("q", "q", "E"), 0.65f, "x"),
                )
            val req =
                SearchRequest
                    .newBuilder()
                    .setResultThreshold(0.6f)
                    .setPage(PageRequest.newBuilder().setPageSize(3))
                    .build()
            val out = postProcess(q, req)
            out.size shouldBe 3
            out.map { it.ownerQname.name } shouldBe listOf("A", "B", "C")
        }

        "post-process threshold drops below-cutoff hits" {
            val hits =
                listOf(
                    hit(qn("q", "q", "A"), 0.9f, "x"),
                    hit(qn("q", "q", "B"), 0.7f, "x"),
                    hit(qn("q", "q", "C"), 0.5f, "x"),
                    hit(qn("q", "q", "D"), 0.3f, "x"),
                    hit(qn("q", "q", "E"), 0.1f, "x"),
                )
            val req = SearchRequest.newBuilder().setResultThreshold(0.6f).build()
            postProcess(hits, req).size shouldBe 2
        }

        "post-process default threshold of 0.0 is a no-op" {
            val hits = listOf(hit(qn("q", "q", "X"), 0.01f, "x"))
            postProcess(hits, SearchRequest.newBuilder().build()) shouldBe hits
        }
    })

private class StaticAlgorithm(
    override val name: String,
    private val staticHits: List<SearchHit>,
) : SearchAlgorithm {
    override fun rebuild(
        snapshot: RegistrySnapshot,
        language: String,
    ): RebuildOutcome = RebuildOutcome(SearchIndex.Empty)

    override fun search(
        request: SearchRequest,
        index: SearchIndex,
    ): List<SearchHit> = staticHits
}

private fun hit(
    qname: QualifiedName,
    score: Float,
    matchedField: String,
    algorithm: String = "test",
): SearchHit =
    SearchHit(
        ownerQname = qname,
        ownerKind = "query",
        score = score,
        matchedField = matchedField,
        matchedValue = "x",
        snippet = "x",
        algorithm = algorithm,
    )

private fun req(query: String): SearchRequest =
    SearchRequest
        .newBuilder()
        .setQuery(query)
        .setLanguage("cs")
        .build()

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

private fun emptySnapshot(): RegistrySnapshot {
    val model =
        Model(
            descriptor = ModelDescriptor("test-id", "test"),
            version = ModelVersion("0", Instant.EPOCH),
            schemas = emptyMap(),
            mappings = emptyList(),
            queries = emptyMap(),
        )
    return RegistrySnapshot(model, ModelGraph.build(model), Instant.EPOCH, emptyList())
}
