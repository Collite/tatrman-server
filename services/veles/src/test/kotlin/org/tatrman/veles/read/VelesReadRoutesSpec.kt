// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.read

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.LocalizedTextList
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.SearchHints
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.search.SearchAlgorithmRegistry
import org.tatrman.ttr.metadata.search.SearchIndexHolder
import org.tatrman.ttr.metadata.search.all.AllAlgorithm
import org.tatrman.ttr.metadata.search.keyword.KeywordAlgorithm
import org.tatrman.ttr.metadata.search.keyword.StopWords
import org.tatrman.ttr.metadata.search.regex.RegexAlgorithm
import org.tatrman.ttr.metadata.search.substring.SubstringAlgorithm
import java.time.Instant

/**
 * Component proof for the read-only catalog JSON surface. Uses Ktor's `testApplication`
 * (the ttr-llm-gateway HealthSpec pattern) driving a hand-built model through the real
 * registry + search stack, so the assertions exercise the same domain APIs the routes
 * call in production.
 */
class VelesReadRoutesSpec :
    StringSpec({

        "index reports schemas, packages, counts and version" {
            testApplication {
                environment { config = MapApplicationConfig() }
                val (registry, sr, holder) = wire(populatedModel())
                application { routing { velesReadRoutes(registry, sr, holder) } }

                client.get("/model/index").let {
                    it.status shouldBe HttpStatusCode.OK
                    val body = it.bodyAsText()
                    body shouldContain "\"schemas\":[\"er\"]"
                    body shouldContain "\"packages\":[\"sales\"]"
                    body shouldContain "\"modelVersion\":\"v1\""
                    // entity + attribute + query all land in objectByQname().
                    body shouldContain "\"objects\":3"
                }
            }
        }

        "index is 503 model not loaded before a snapshot swaps" {
            testApplication {
                environment { config = MapApplicationConfig() }
                val registry = MetadataRegistry()
                val (sr, holder) = searchStack()
                application { routing { velesReadRoutes(registry, sr, holder) } }

                client.get("/model/index").let {
                    it.status shouldBe HttpStatusCode.ServiceUnavailable
                    it.bodyAsText() shouldContain """{"error":"model not loaded"}"""
                }
            }
        }

        "graph exposes nodes and the attribute→entity DEFINES edge" {
            testApplication {
                environment { config = MapApplicationConfig() }
                val (registry, sr, holder) = wire(populatedModel())
                application { routing { velesReadRoutes(registry, sr, holder) } }

                client.get("/model/graph").let {
                    it.status shouldBe HttpStatusCode.OK
                    val body = it.bodyAsText()
                    body shouldContain "\"qname\":\"er.sales.order\""
                    body shouldContain "\"type\":\"DEFINES\""
                }
            }
        }

        "object returns the descriptor + string sourceLocation, 404 on miss" {
            testApplication {
                environment { config = MapApplicationConfig() }
                val (registry, sr, holder) = wire(populatedModel())
                application { routing { velesReadRoutes(registry, sr, holder) } }

                client.get("/model/object?qname=er.sales.order").let {
                    it.status shouldBe HttpStatusCode.OK
                    val body = it.bodyAsText()
                    body shouldContain "\"qname\":\"er.sales.order\""
                    body shouldContain "\"sourceLocation\":"
                    body shouldContain "\"references\":"
                }

                client.get("/model/object?qname=er.sales.nope").let {
                    it.status shouldBe HttpStatusCode.NotFound
                    it.bodyAsText() shouldContain """{"error":"not found"}"""
                }
            }
        }

        "search reuses the gRPC path; blank query is empty" {
            testApplication {
                environment { config = MapApplicationConfig() }
                val (registry, sr, holder) = wire(populatedModel())
                application { routing { velesReadRoutes(registry, sr, holder) } }

                client.get("/model/search?query=ordersList").let {
                    it.status shouldBe HttpStatusCode.OK
                    val body = it.bodyAsText()
                    body shouldContain "\"hits\":["
                    body shouldContain "\"matchedField\":\"name\""
                }

                client.get("/model/search?query=").let {
                    it.status shouldBe HttpStatusCode.OK
                    it.bodyAsText() shouldBe """{"hits":[]}"""
                }
            }
        }
    })

private data class Wired(
    val registry: MetadataRegistry,
    val searchRegistry: SearchAlgorithmRegistry,
    val indexHolder: SearchIndexHolder,
)

private fun searchStack(): Pair<SearchAlgorithmRegistry, SearchIndexHolder> {
    val languages = listOf("cs", "en")
    val stopWords = StopWords(languages)
    val substring = SubstringAlgorithm()
    val keyword = KeywordAlgorithm(stopWords)
    val regex = RegexAlgorithm()
    val core =
        SearchAlgorithmRegistry(
            mapOf(substring.name to substring, keyword.name to keyword, regex.name to regex),
        )
    val holder = SearchIndexHolder(core, languages)
    val all = AllAlgorithm(core, holder)
    val withAll = SearchAlgorithmRegistry(core.all().associateBy { it.name } + (all.name to all))
    return withAll to holder
}

private fun wire(model: Model): Wired {
    val registry = MetadataRegistry()
    val (withAll, holder) = searchStack()
    registry.addListener { snap -> holder.rebuild(snap) }
    registry.swap(model, ModelGraph.build(model))
    return Wired(registry, withAll, holder)
}

private fun qn(
    schema: SchemaCode,
    namespace: String,
    name: String,
    pkg: String = "",
): QualifiedName = QualifiedName(schemaCode = schema, namespace = namespace, name = name, `package` = pkg)

/** One entity + its attribute (yields a DEFINES edge) + a matchable query. */
private fun populatedModel(): Model {
    val entityQn = qn(SchemaCode.ER, "sales", "order", pkg = "sales")
    val attrQn = qn(SchemaCode.ER, "sales", "order.total", pkg = "sales")
    val entity =
        Entity(
            internalId = "e1",
            qname = entityQn,
            sourceFile = "/model/sales/order.ttr",
            attributes =
                listOf(
                    Attribute(
                        internalId = "a1",
                        qname = attrQn,
                        sourceFile = "/model/sales/order.ttr",
                        entity = entityQn,
                        type = "decimal",
                    ),
                ),
        )
    val query =
        Query(
            internalId = "q1",
            qname = qn(SchemaCode.UNSPECIFIED, "query", "ordersList", pkg = "sales"),
            sourceLanguage = "SQL",
            sourceText = "select 1",
            search = SearchHints(keywords = LocalizedTextList(mapOf("cs" to listOf("objednávky")))),
        )
    return Model(
        descriptor = ModelDescriptor("test-id", "test"),
        version = ModelVersion("v1", Instant.EPOCH),
        schemas = mapOf("er" to ErSchema(entities = mapOf(entityQn to entity))),
        mappings = emptyList(),
        queries = mapOf(query.qname to query),
    )
}
