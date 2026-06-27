package org.tatrman.kantheon.ariadne.grpc

import org.tatrman.ariadne.v1.ListObjectsRequest
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.parseSchemaCode
import org.tatrman.kantheon.ariadne.graph.ModelGraph
import org.tatrman.kantheon.ariadne.model.DbColumn
import org.tatrman.kantheon.ariadne.model.DbSchema
import org.tatrman.kantheon.ariadne.model.DbTable
import org.tatrman.kantheon.ariadne.model.Entity
import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.model.ModelVersion
import org.tatrman.kantheon.ariadne.model.SearchHints
import org.tatrman.kantheon.ariadne.registry.MetadataRegistry
import org.tatrman.kantheon.ariadne.search.SearchAlgorithmRegistry
import org.tatrman.kantheon.ariadne.search.SearchIndexHolder
import org.tatrman.kantheon.ariadne.search.all.AllAlgorithm
import org.tatrman.kantheon.ariadne.search.keyword.KeywordAlgorithm
import org.tatrman.kantheon.ariadne.search.keyword.StopWords
import org.tatrman.kantheon.ariadne.search.regex.RegexAlgorithm
import org.tatrman.kantheon.ariadne.search.substring.SubstringAlgorithm
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class ListObjectsFuzzyOnlyFilterSpec :
    StringSpec({

        fun qn(
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

        "ListObjects(kind=column, fuzzy_only=false) returns all columns (entity excluded by kind)" {
            val table =
                DbTable(
                    internalId = "t1",
                    qname = qn("db", "dbo", "customers"),
                    columns =
                        listOf(
                            column(qn("db", "dbo", "customers.id"), "int", fuzzy = false, searchable = false),
                            column(qn("db", "dbo", "customers.name"), "text", fuzzy = true, searchable = true),
                            column(qn("db", "dbo", "customers.email"), "varchar", fuzzy = false, searchable = false),
                        ),
                    primaryKey = listOf("id"),
                )
            val fuzzyEntity =
                Entity(
                    internalId = "e1",
                    qname = qn("er", "entity", "Customer"),
                    search = SearchHints(fuzzy = true, searchable = true),
                )
            val model =
                Model(
                    descriptor = ModelDescriptor("test-id", "test"),
                    version = ModelVersion("0", java.time.Instant.EPOCH),
                    schemas =
                        mapOf(
                            "db" to DbSchema(tables = mapOf(table.qname to table)),
                            "er" to
                                org.tatrman.kantheon.ariadne.model.ErSchema(
                                    entities =
                                        mapOf(
                                            fuzzyEntity.qname to fuzzyEntity,
                                        ),
                                ),
                        ),
                    mappings = emptyList(),
                    queries = emptyMap(),
                )
            val (service, _) = wire(model)

            val resp =
                service.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("column")
                        .setFuzzyOnly(false)
                        .build(),
                )

            resp.itemsCount shouldBe 3
            resp.itemsList.map { it.qualifiedName.name }.toSet() shouldBe
                setOf("customers.id", "customers.name", "customers.email")
            resp.itemsList.map { it.qualifiedName.name } shouldNotContain "Customer"
        }

        "ListObjects(kind=column, fuzzy_only=true) returns only fuzzy columns" {
            val table =
                DbTable(
                    internalId = "t1",
                    qname = qn("db", "dbo", "customers"),
                    columns =
                        listOf(
                            column(qn("db", "dbo", "customers.id"), "int", fuzzy = false, searchable = false),
                            column(qn("db", "dbo", "customers.name"), "text", fuzzy = true, searchable = true),
                            column(qn("db", "dbo", "customers.email"), "varchar", fuzzy = true, searchable = true),
                        ),
                    primaryKey = listOf("id"),
                )
            val fuzzyEntity =
                Entity(
                    internalId = "e1",
                    qname = qn("er", "entity", "Customer"),
                    search = SearchHints(fuzzy = true, searchable = true),
                )
            val model =
                Model(
                    descriptor = ModelDescriptor("test-id", "test"),
                    version = ModelVersion("0", java.time.Instant.EPOCH),
                    schemas =
                        mapOf(
                            "db" to DbSchema(tables = mapOf(table.qname to table)),
                            "er" to
                                org.tatrman.kantheon.ariadne.model.ErSchema(
                                    entities =
                                        mapOf(
                                            fuzzyEntity.qname to fuzzyEntity,
                                        ),
                                ),
                        ),
                    mappings = emptyList(),
                    queries = emptyMap(),
                )
            val (service, _) = wire(model)

            val resp =
                service.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("column")
                        .setFuzzyOnly(true)
                        .build(),
                )

            resp.itemsCount shouldBe 2
            resp.itemsList.map { it.qualifiedName.name }.toSet() shouldBe
                setOf("customers.name", "customers.email")
            resp.itemsList.map { it.qualifiedName.name } shouldNotContain "Customer"
        }

        "ListObjects(kind=entity, fuzzy_only=true) returns only fuzzy entities (cross-kind)" {
            val entity =
                Entity(
                    internalId = "e1",
                    qname = qn("er", "entity", "Customer"),
                    search = SearchHints(fuzzy = true, searchable = true),
                )
            val nonFuzzyEntity =
                Entity(
                    internalId = "e2",
                    qname = qn("er", "entity", "Order"),
                    search = SearchHints.EMPTY,
                )
            val model =
                Model(
                    descriptor = ModelDescriptor("test-id", "test"),
                    version = ModelVersion("0", java.time.Instant.EPOCH),
                    schemas =
                        mapOf(
                            "er" to
                                org.tatrman.kantheon.ariadne.model.ErSchema(
                                    entities = mapOf(entity.qname to entity, nonFuzzyEntity.qname to nonFuzzyEntity),
                                ),
                        ),
                    mappings = emptyList(),
                    queries = emptyMap(),
                )
            val (service, _) = wire(model)

            val resp =
                service.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("entity")
                        .setFuzzyOnly(true)
                        .build(),
                )

            resp.itemsCount shouldBe 1
            resp.itemsList[0].qualifiedName.name shouldBe "Customer"
        }

        "ListObjects(kind=column, fuzzy_only=true) includes columns backing a fuzzy attribute" {
            // The `fuzzy: true` flag now lives on ER attributes; the column itself
            // is NOT marked fuzzy. The effective-fuzzy resolution must follow the
            // er2db mapping and surface the backing column anyway.
            val table =
                DbTable(
                    internalId = "t1",
                    qname = qn("db", "dbo", "QSTRED_DF"),
                    columns =
                        listOf(
                            column(qn("db", "dbo", "QSTRED_DF.IDSTRED"), "int", fuzzy = false, searchable = false),
                            column(qn("db", "dbo", "QSTRED_DF.KOD_STR"), "text", fuzzy = false, searchable = false),
                            column(qn("db", "dbo", "QSTRED_DF.NAZEV_STR"), "text", fuzzy = false, searchable = false),
                        ),
                    primaryKey = listOf("IDSTRED"),
                )
            val kodAttr =
                org.tatrman.kantheon.ariadne.model.Attribute(
                    internalId = "a-kod",
                    qname = qn("er", "entity", "účetní_středisko.kód_střediska"),
                    entity = qn("er", "entity", "účetní_středisko"),
                    type = "text",
                    search = SearchHints(fuzzy = true, searchable = true),
                )
            val nazevAttr =
                org.tatrman.kantheon.ariadne.model.Attribute(
                    internalId = "a-nazev",
                    qname = qn("er", "entity", "účetní_středisko.název_střediska"),
                    entity = qn("er", "entity", "účetní_středisko"),
                    type = "text",
                    search = SearchHints(fuzzy = true, searchable = true),
                )
            val entity =
                Entity(
                    internalId = "e1",
                    qname = qn("er", "entity", "účetní_středisko"),
                    aliases = listOf("středisko", "nákladové středisko"),
                    attributes = listOf(kodAttr, nazevAttr),
                )
            val mappings =
                listOf(
                    org.tatrman.kantheon.ariadne.model.Er2DbAttributeMapping(
                        internalId = "m-kod",
                        qname = qn("er", "map", "kod"),
                        attribute = kodAttr.qname,
                        target =
                            org.tatrman.kantheon.ariadne.model.AttributeMappingTarget.Column(
                                qn("db", "dbo", "QSTRED_DF.KOD_STR"),
                            ),
                    ),
                    org.tatrman.kantheon.ariadne.model.Er2DbAttributeMapping(
                        internalId = "m-nazev",
                        qname = qn("er", "map", "nazev"),
                        attribute = nazevAttr.qname,
                        target =
                            org.tatrman.kantheon.ariadne.model.AttributeMappingTarget.Column(
                                qn("db", "dbo", "QSTRED_DF.NAZEV_STR"),
                            ),
                    ),
                )
            val model =
                Model(
                    descriptor = ModelDescriptor("test-id", "test"),
                    version = ModelVersion("0", java.time.Instant.EPOCH),
                    schemas =
                        mapOf(
                            "db" to DbSchema(tables = mapOf(table.qname to table)),
                            "er" to
                                org.tatrman.kantheon.ariadne.model
                                    .ErSchema(entities = mapOf(entity.qname to entity)),
                        ),
                    mappings = mappings,
                    queries = emptyMap(),
                )
            val (service, _) = wire(model)

            val resp =
                service.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("column")
                        .setFuzzyOnly(true)
                        .build(),
                )

            // KOD_STR + NAZEV_STR surface via their fuzzy attributes; IDSTRED does not.
            resp.itemsList.map { it.qualifiedName.name }.toSet() shouldBe
                setOf("QSTRED_DF.KOD_STR", "QSTRED_DF.NAZEV_STR")
        }

        "ListObjects(kind=column, fuzzy_only=true) returns empty when no fuzzy columns exist" {
            val table =
                DbTable(
                    internalId = "t1",
                    qname = qn("db", "dbo", "products"),
                    columns =
                        listOf(
                            column(qn("db", "dbo", "products.id"), "int", fuzzy = false, searchable = false),
                            column(qn("db", "dbo", "products.name"), "text", fuzzy = false, searchable = false),
                        ),
                    primaryKey = listOf("id"),
                )
            val model = modelOf(table)
            val (service, _) = wire(model)

            val resp =
                service.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("column")
                        .setFuzzyOnly(true)
                        .build(),
                )

            resp.itemsCount shouldBe 0
        }
    })

private fun column(
    qname: QualifiedName,
    dataType: String,
    fuzzy: Boolean = false,
    searchable: Boolean = false,
): DbColumn =
    DbColumn(
        internalId = "c-${qname.name}",
        qname = qname,
        table = qname,
        dataType = dataType,
        search = SearchHints(fuzzy = fuzzy, searchable = searchable),
    )

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
