package org.tatrman.kantheon.ariadne.export

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.parseSchemaCode
import org.tatrman.kantheon.ariadne.model.Attribute
import org.tatrman.kantheon.ariadne.model.Cardinality
import org.tatrman.kantheon.ariadne.model.CncSchema
import org.tatrman.kantheon.ariadne.model.DbColumn
import org.tatrman.kantheon.ariadne.model.DbProcedure
import org.tatrman.kantheon.ariadne.model.DbProcedureParameter
import org.tatrman.kantheon.ariadne.model.DbSchema
import org.tatrman.kantheon.ariadne.model.DbTable
import org.tatrman.kantheon.ariadne.model.ParameterDirection
import org.tatrman.kantheon.ariadne.model.Entity
import org.tatrman.kantheon.ariadne.model.Er2CncRoleMapping
import org.tatrman.kantheon.ariadne.model.Er2DbAttributeMapping
import org.tatrman.kantheon.ariadne.model.Er2DbEntityMapping
import org.tatrman.kantheon.ariadne.model.ErSchema
import org.tatrman.kantheon.ariadne.model.LocalizedText
import org.tatrman.kantheon.ariadne.model.MappingTarget
import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.model.ModelVersion
import org.tatrman.kantheon.ariadne.model.Query
import org.tatrman.kantheon.ariadne.model.QueryParameterDef
import org.tatrman.kantheon.ariadne.model.Relation
import org.tatrman.kantheon.ariadne.model.Role
import org.tatrman.kantheon.ariadne.model.SearchHints
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.writer.TtrRenderer
import java.time.Instant

class MetadataExportPipelineSpec :
    StringSpec({

        fun qn(
            schema: String,
            ns: String,
            name: String,
        ): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(parseSchemaCode(schema) ?: SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                .setNamespace(ns)
                .setName(name)
                .build()

        fun minimalModel(): Model {
            val tableQn = qn("db", "dbo", "customers")
            val colQn = qn("db", "dbo", "customers.id")
            val entityQn = qn("er", "entity", "customer")
            val attrQn = qn("er", "entity", "customer.id")
            val mappingQn = qn("map", "er2db_entity", "customer")
            val attrMappingQn = qn("map", "er2db_attribute", "customer.id")

            val col =
                DbColumn(
                    internalId = "c1",
                    qname = colQn,
                    table = tableQn,
                    dataType = "int",
                    isPrimaryKey = true,
                )
            val table =
                DbTable(
                    internalId = "t1",
                    qname = tableQn,
                    primaryKey = listOf("id"),
                    columns = listOf(col),
                )
            val attr =
                Attribute(
                    internalId = "a1",
                    qname = attrQn,
                    entity = entityQn,
                    type = "int",
                    isKey = true,
                    displayLabel = LocalizedText(emptyMap()),
                )
            val entity =
                Entity(
                    internalId = "e1",
                    qname = entityQn,
                    attributes = listOf(attr),
                    displayLabel = LocalizedText(emptyMap()),
                )
            val entityMapping =
                Er2DbEntityMapping(
                    internalId = "m1",
                    qname = mappingQn,
                    entity = entityQn,
                    target = MappingTarget.Table(tableQn),
                )
            val attrMapping =
                Er2DbAttributeMapping(
                    internalId = "m2",
                    qname = attrMappingQn,
                    attribute = attrQn,
                    target =
                        org.tatrman.kantheon.ariadne.model.AttributeMappingTarget
                            .Column(colQn),
                )
            val stockRole =
                Role(
                    internalId = "r1",
                    qname = qn("cnc", "role", "fact"),
                    label = LocalizedText(mapOf("en" to "Fact entity")),
                    sourceFile = "builtin/cnc-stock-roles.ttr",
                    search = SearchHints.EMPTY,
                )
            val userRole =
                Role(
                    internalId = "r2",
                    qname = qn("cnc", "role", "custom"),
                    label = LocalizedText(mapOf("en" to "Custom role")),
                    search = SearchHints.EMPTY,
                )

            // v2.1: er2db entity/attribute mappings are inline; only the er2cnc_role
            // mapping remains standalone in the `map` bucket (Stage 2 keeps it there;
            // Stage 3 rebuckets it to cnc).
            val er2cnc =
                Er2CncRoleMapping(
                    internalId = "m3",
                    qname = qn("map", "er2cnc_role", "customer__fact"),
                    entity = entityQn,
                    role = qn("cnc", "role", "fact"),
                )

            return Model(
                descriptor = ModelDescriptor(id = "test", name = "Test", description = ""),
                version = ModelVersion(value = "v-test", swappedAt = Instant.now()),
                schemas =
                    mapOf(
                        "er" to ErSchema(entities = mapOf(entityQn to entity)),
                        "db" to DbSchema(tables = mapOf(tableQn to table)),
                        "cnc" to CncSchema(roles = mapOf(stockRole.qname to stockRole, userRole.qname to userRole)),
                    ),
                mappings = listOf(entityMapping, attrMapping, er2cnc),
                queries = emptyMap(),
            )
        }

        "convert produces er, db, cnc files (no map.ttr; er2db inline, er2cnc in cnc)" {
            val bundle = ModelToDefinitions.convert(minimalModel())
            val filenames = bundle.files.map { it.filename }
            filenames shouldContain "er.ttr"
            filenames shouldContain "db.ttr"
            filenames shouldContain "cnc.ttr"
            filenames shouldNotContain "map.ttr"
            // er2cnc_role is standalone in cnc.ttr; er2db_* are inline on their defs.
            val cncFile = bundle.files.first { it.filename == "cnc.ttr" }
            val cncContent = TtrRenderer.renderFile(cncFile.schemaCode, cncFile.namespace, cncFile.definitions)
            cncContent shouldContain "def er2cnc_role customer__fact"
            cncContent.shouldNotContain("def er2db_entity")
            cncContent.shouldNotContain("def er2db_attribute")
            // the entity carries the binding inline instead
            val erFile = bundle.files.first { it.filename == "er.ttr" }
            val erContent = TtrRenderer.renderFile(erFile.schemaCode, erFile.namespace, erFile.definitions)
            erContent shouldContain "binding:"
        }

        "each exported file parses cleanly via TtrLoader" {
            val bundle = ModelToDefinitions.convert(minimalModel())
            for (file in bundle.files) {
                val content = TtrRenderer.renderFile(file.schemaCode, file.namespace, file.definitions)
                val result = TtrLoader.parseString(content, fileLabel = file.filename)
                result.ok shouldBe true
                result.errors.isEmpty() shouldBe true
            }
        }

        "stock roles are excluded from the cnc export file" {
            val bundle = ModelToDefinitions.convert(minimalModel())
            val cncFile = bundle.files.first { it.filename == "cnc.ttr" }
            val content = TtrRenderer.renderFile(cncFile.schemaCode, cncFile.namespace, cncFile.definitions)
            content shouldContain "def role custom"
            content.shouldNotContain("def role fact")
        }

        "er.ttr carries no schema directive (kind-derived) and holds the entity" {
            val bundle = ModelToDefinitions.convert(minimalModel())
            val erFile = bundle.files.first { it.filename == "er.ttr" }
            erFile.schemaCode shouldBe null
            erFile.namespace shouldBe null
            val content = TtrRenderer.renderFile(erFile.schemaCode, erFile.namespace, erFile.definitions)
            content.shouldNotContain("schema er")
            content shouldContain "def entity customer"
        }

        "db.ttr carries no schema directive (kind-derived) and holds table + columns" {
            val bundle = ModelToDefinitions.convert(minimalModel())
            val dbFile = bundle.files.first { it.filename == "db.ttr" }
            dbFile.schemaCode shouldBe null
            dbFile.namespace shouldBe null
            val content = TtrRenderer.renderFile(dbFile.schemaCode, dbFile.namespace, dbFile.definitions)
            content.shouldNotContain("schema db")
            content shouldContain "def table customers"
            content shouldContain "def column id"
        }

        "no map.ttr is produced (er2db inline, er2cnc folded into cnc.ttr)" {
            val bundle = ModelToDefinitions.convert(minimalModel())
            bundle.files.none { it.filename == "map.ttr" } shouldBe true
        }

        "cardinality is rendered as range-string object" {
            val relQn = qn("er", "relation", "test_rel")
            val entityQn = qn("er", "entity", "customer")
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "test", name = "Test", description = ""),
                    version = ModelVersion(value = "v-test", swappedAt = Instant.now()),
                    schemas =
                        mapOf(
                            "er" to
                                ErSchema(
                                    relations =
                                        mapOf(
                                            relQn to
                                                Relation(
                                                    internalId = "r1",
                                                    qname = relQn,
                                                    fromEntity = entityQn,
                                                    toEntity = entityQn,
                                                    cardinality = Cardinality(0, -1, 0, 1),
                                                ),
                                        ),
                                ),
                        ),
                    mappings = emptyList(),
                    queries = emptyMap(),
                )
            val bundle = ModelToDefinitions.convert(model)
            // Relations now fold into `er.ttr` (directive-less; relation derives its schema by kind).
            val erFile = bundle.files.first { it.filename == "er.ttr" }
            val content = TtrRenderer.renderFile(erFile.schemaCode, erFile.namespace, erFile.definitions)
            content shouldContain "def relation test_rel"
            content shouldContain "\"0..*\""
            content shouldContain "\"0..1\""
            val result = TtrLoader.parseString(content, fileLabel = "er.ttr")
            result.ok shouldBe true
        }

        "query.ttr is produced when model has queries" {
            val qn = qn("query", "query", "find_all")
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "test", name = "Test", description = ""),
                    version = ModelVersion(value = "v-test", swappedAt = Instant.now()),
                    schemas = emptyMap(),
                    mappings = emptyList(),
                    queries =
                        mapOf(
                            qn to
                                Query(
                                    internalId = "q1",
                                    qname = qn,
                                    sourceLanguage = "SQL",
                                    sourceText = "SELECT 1",
                                ),
                        ),
                )
            val bundle = ModelToDefinitions.convert(model)
            // Queries now live in `db.ttr` (directive-less; query derives its schema by kind).
            val queryFile = bundle.files.first { it.filename == "db.ttr" }
            val content = TtrRenderer.renderFile(queryFile.schemaCode, queryFile.namespace, queryFile.definitions)
            content.shouldNotContain("schema query")
            content shouldContain "def query find_all"
            val result = TtrLoader.parseString(content, fileLabel = "db.ttr")
            result.ok shouldBe true
        }

        "procedure parameters (with direction) round-trip through the export pipeline" {
            val procQn = qn("db", "dbo", "sp_find_customers")
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "test", name = "Test", description = ""),
                    version = ModelVersion(value = "v-test", swappedAt = Instant.now()),
                    schemas =
                        mapOf(
                            "db" to
                                DbSchema(
                                    procedures =
                                        mapOf(
                                            procQn to
                                                DbProcedure(
                                                    internalId = "p1",
                                                    qname = procQn,
                                                    parameters =
                                                        listOf(
                                                            DbProcedureParameter(
                                                                name = "id_subjektu",
                                                                dataType = "varchar",
                                                                direction = ParameterDirection.IN,
                                                            ),
                                                            DbProcedureParameter(
                                                                name = "row_count",
                                                                dataType = "int",
                                                                direction = ParameterDirection.OUT,
                                                            ),
                                                        ),
                                                ),
                                        ),
                                ),
                        ),
                    mappings = emptyList(),
                    queries = emptyMap(),
                )
            val bundle = ModelToDefinitions.convert(model)
            val dbFile = bundle.files.first { it.filename == "db.ttr" }
            val content = TtrRenderer.renderFile(dbFile.schemaCode, dbFile.namespace, dbFile.definitions)
            content shouldContain "def procedure sp_find_customers"
            content shouldContain "name: id_subjektu"
            content shouldContain "direction: IN"
            content shouldContain "direction: OUT"
            val result = TtrLoader.parseString(content, fileLabel = "db.ttr")
            result.ok shouldBe true
            result.errors.isEmpty() shouldBe true
        }

        "query parameters are exported in the typed form and parse cleanly" {
            val qn = qn("query", "query", "find_customers")
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "test", name = "Test", description = ""),
                    version = ModelVersion(value = "v-test", swappedAt = Instant.now()),
                    schemas = emptyMap(),
                    mappings = emptyList(),
                    queries =
                        mapOf(
                            qn to
                                Query(
                                    internalId = "q1",
                                    qname = qn,
                                    sourceLanguage = "SQL",
                                    sourceText = "SELECT * FROM customers WHERE nazev LIKE @nazev_zakaznika",
                                    parameters =
                                        listOf(
                                            QueryParameterDef(
                                                name = "nazev_zakaznika",
                                                type = "varchar",
                                                label = "Název zákazníka",
                                            ),
                                            QueryParameterDef(name = "id_subjektu", type = "varchar"),
                                        ),
                                ),
                        ),
                )
            val bundle = ModelToDefinitions.convert(model)
            val queryFile = bundle.files.first { it.filename == "db.ttr" }
            val content = TtrRenderer.renderFile(queryFile.schemaCode, queryFile.namespace, queryFile.definitions)
            content shouldContain "name: nazev_zakaznika"
            content shouldContain "type: varchar"
            content shouldContain "label: \"Název zákazníka\""
            val result = TtrLoader.parseString(content, fileLabel = "db.ttr")
            result.ok shouldBe true
            result.errors.isEmpty() shouldBe true
        }

        "query search hints are exported as a search { } block and parse cleanly" {
            val qn = qn("query", "query", "list_dummies")
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "test", name = "Test", description = ""),
                    version = ModelVersion(value = "v-test", swappedAt = Instant.now()),
                    schemas = emptyMap(),
                    mappings = emptyList(),
                    queries =
                        mapOf(
                            qn to
                                Query(
                                    internalId = "q1",
                                    qname = qn,
                                    sourceLanguage = "SQL",
                                    sourceText = "SELECT * FROM DUMMY",
                                    search =
                                        SearchHints(
                                            patterns = listOf("seznam (vsech |všech |)dummy", "list (of |)dummies"),
                                            examples = listOf("Seznam všech dummy"),
                                        ),
                                ),
                        ),
                )
            val bundle = ModelToDefinitions.convert(model)
            val queryFile = bundle.files.first { it.filename == "db.ttr" }
            val content = TtrRenderer.renderFile(queryFile.schemaCode, queryFile.namespace, queryFile.definitions)
            content shouldContain "search {"
            content shouldContain "patterns: [\"seznam (vsech |všech |)dummy\", \"list (of |)dummies\"]"
            content shouldContain "examples: [\"Seznam všech dummy\"]"
            val result = TtrLoader.parseString(content, fileLabel = "db.ttr")
            result.ok shouldBe true
            result.errors.isEmpty() shouldBe true
        }
    })
