package org.tatrman.kantheon.ariadne.grpc

import org.tatrman.ariadne.v1.GetObjectRequest
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.parseSchemaCode
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.kantheon.ariadne.grpc.toProto
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.LocalizedTextList
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.SearchHints
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.search.SearchAlgorithmRegistry
import org.tatrman.ttr.metadata.search.SearchIndexHolder
import org.tatrman.ttr.metadata.search.all.AllAlgorithm
import org.tatrman.ttr.metadata.search.keyword.KeywordAlgorithm
import org.tatrman.ttr.metadata.search.keyword.StopWords
import org.tatrman.ttr.metadata.search.regex.RegexAlgorithm
import org.tatrman.ttr.metadata.search.substring.SubstringAlgorithm
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GetObjectColumnSearchHintsSpec :
    StringSpec({

        fun qn(
            schema: String,
            namespace: String,
            name: String,
        ): QualifiedName =
            QualifiedName(
                schemaCode = parseSchemaCode(schema) ?: SchemaCode.UNSPECIFIED,
                namespace = namespace,
                name = name,
            )

        "GetObject returns column with search.fuzzy=true when DbColumn.search.fuzzy is set" {
            val customersQ = qn("db", "dbo", "customers")
            val nameQ = qn("db", "dbo", "customers.name")
            val table =
                DbTable(
                    internalId = "t1",
                    qname = customersQ,
                    columns =
                        listOf(
                            DbColumn(
                                internalId = "c1",
                                qname = nameQ,
                                table = customersQ,
                                dataType = "varchar",
                                search = SearchHints(fuzzy = true, searchable = true),
                            ),
                        ),
                    primaryKey = listOf("id"),
                )
            val model = modelOf(table)
            val (service, _) = wire(model)

            val req = GetObjectRequest.newBuilder().setQualifiedName(nameQ.toProto()).build()
            val resp = service.getObject(req)

            resp.column.search.fuzzy shouldBe true
            resp.column.search.searchable shouldBe true
        }

        "GetObject returns column with no search field when DbColumn.search is EMPTY" {
            val customersQ = qn("db", "dbo", "customers")
            val idQ = qn("db", "dbo", "customers.id")
            val table =
                DbTable(
                    internalId = "t1",
                    qname = customersQ,
                    columns =
                        listOf(
                            DbColumn(
                                internalId = "c1",
                                qname = idQ,
                                table = customersQ,
                                dataType = "int",
                                search = SearchHints.EMPTY,
                            ),
                        ),
                    primaryKey = listOf("id"),
                )
            val model = modelOf(table)
            val (service, _) = wire(model)

            val req = GetObjectRequest.newBuilder().setQualifiedName(idQ.toProto()).build()
            val resp = service.getObject(req)

            resp.column.hasSearch() shouldBe false
        }

        "GetObject returns column with search populated from keywords and aliases" {
            val ordersQ = qn("db", "dbo", "orders")
            val statusQ = qn("db", "dbo", "orders.status")
            val table =
                DbTable(
                    internalId = "t1",
                    qname = ordersQ,
                    columns =
                        listOf(
                            DbColumn(
                                internalId = "c1",
                                qname = statusQ,
                                table = ordersQ,
                                dataType = "varchar",
                                search =
                                    SearchHints(
                                        fuzzy = false,
                                        searchable = true,
                                        keywords = LocalizedTextList(mapOf("cs" to listOf("stav", "status"))),
                                        aliases = listOf("order_status"),
                                    ),
                            ),
                        ),
                    primaryKey = listOf("id"),
                )
            val model = modelOf(table)
            val (service, _) = wire(model)

            val req = GetObjectRequest.newBuilder().setQualifiedName(statusQ.toProto()).build()
            val resp = service.getObject(req)

            resp.column.search.searchable shouldBe true
            resp.column.search.fuzzy shouldBe false
            resp.column.search.aliasesList shouldBe listOf("order_status")
        }
    })

private fun modelOf(vararg tables: DbTable): Model =
    Model(
        descriptor = ModelDescriptor("test-id", "test"),
        version = ModelVersion("0", java.time.Instant.EPOCH),
        schemas = mapOf("db" to DbSchema(tables = tables.associateBy { it.qname })),
        mappings = emptyList(),
        queries = emptyMap(),
    )

private fun wire(model: Model): Pair<MetadataServiceImpl, MetadataRegistry> {
    val registry = MetadataRegistry()
    val stopWords = StopWords(listOf("cs", "en"))
    val substring = SubstringAlgorithm()
    val keyword = KeywordAlgorithm(stopWords)
    val regex = RegexAlgorithm()
    val coreRegistry =
        SearchAlgorithmRegistry(mapOf(substring.name to substring, keyword.name to keyword, regex.name to regex))
    val holder = SearchIndexHolder(coreRegistry, listOf("cs", "en"))
    val all = AllAlgorithm(coreRegistry, holder)
    val withAll =
        SearchAlgorithmRegistry(
            coreRegistry.all().associateBy { it.name } + (all.name to all),
        )
    registry.addListener { snap -> holder.rebuild(snap) }
    registry.swap(model, ModelGraph.build(model))
    val service = MetadataServiceImpl(registry, withAll, holder, tracer = null)
    return service to registry
}
