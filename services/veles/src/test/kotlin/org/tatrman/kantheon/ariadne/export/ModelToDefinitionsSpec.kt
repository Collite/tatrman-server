package org.tatrman.kantheon.ariadne.export

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.parseSchemaCode
import org.tatrman.kantheon.ariadne.model.Attribute
import org.tatrman.kantheon.ariadne.model.AttributeMappingTarget
import org.tatrman.kantheon.ariadne.model.AttributeJoinPair
import org.tatrman.kantheon.ariadne.model.Cardinality
import org.tatrman.kantheon.ariadne.model.DbColumn
import org.tatrman.kantheon.ariadne.model.DbSchema
import org.tatrman.kantheon.ariadne.model.DbTable
import org.tatrman.kantheon.ariadne.model.Er2CncRoleMapping
import org.tatrman.kantheon.ariadne.model.Er2DbAttributeMapping
import org.tatrman.kantheon.ariadne.model.Er2DbEntityMapping
import org.tatrman.kantheon.ariadne.model.Er2DbRelationMapping
import org.tatrman.kantheon.ariadne.model.ErSchema
import org.tatrman.kantheon.ariadne.model.Entity
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.TargetObjectValue
import org.tatrman.ttr.writer.TtrRenderer
import java.time.Instant

class ModelToDefinitionsSpec :
    StringSpec({

        fun qn(
            schemaCode: String,
            namespace: String,
            name: String,
        ): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(parseSchemaCode(schemaCode) ?: SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                .setNamespace(namespace)
                .setName(name)
                .build()

        "roleToRoleDef converts Role to RoleDef with localised label" {
            val role =
                Role(
                    internalId = "r1",
                    qname = qn("cnc", "role", "fact"),
                    description = "A fact entity",
                    tags = listOf("core"),
                    label = LocalizedText(mapOf("cs" to "Faktová entita", "en" to "Fact entity")),
                    search = SearchHints.EMPTY,
                )
            val def = ModelToDefinitions.roleToRoleDef(role)
            def.name shouldBe "fact"
            def.description shouldBe "A fact entity"
            def.tags shouldBe listOf("core")
            def.label?.byLanguage?.get("cs") shouldBe "Faktová entita"
            def.label?.byLanguage?.get("en") shouldBe "Fact entity"
        }

        "roleToRoleDef handles empty label" {
            val role =
                Role(
                    internalId = "r1",
                    qname = qn("cnc", "role", "structural"),
                    search = SearchHints.EMPTY,
                )
            val def = ModelToDefinitions.roleToRoleDef(role)
            def.name shouldBe "structural"
            def.label?.byLanguage?.isEmpty() shouldBe true
        }

        "tableToTableDef converts DbTable to TableDef with columns" {
            val table =
                DbTable(
                    internalId = "t1",
                    qname = qn("db", "dbo", "customers"),
                    description = "Customer table",
                    tags = emptyList(),
                    primaryKey = listOf("id"),
                    columns =
                        listOf(
                            DbColumn(
                                internalId = "c1",
                                qname = qn("db", "dbo", "customers.id"),
                                dataType = "int",
                                table = qn("db", "dbo", "customers"),
                                isPrimaryKey = true,
                            ),
                            DbColumn(
                                internalId = "c2",
                                qname = qn("db", "dbo", "customers.name"),
                                dataType = "varchar(100)",
                                table = qn("db", "dbo", "customers"),
                            ),
                        ),
                )
            val def = ModelToDefinitions.tableToTableDef(table)
            def.name shouldBe "customers"
            def.description shouldBe "Customer table"
            def.primaryKey shouldBe listOf("id")
            def.columns.size shouldBe 2
            def.columns[0].name shouldBe "id"
            def.columns[0].type?.name shouldBe "int"
            def.columns[1].name shouldBe "name"
            def.columns[1].type?.name shouldBe "text"
        }

        "er2dbEntityMappingToDef converts Er2DbEntityMapping with Table target" {
            val mapping =
                Er2DbEntityMapping(
                    internalId = "m1",
                    qname = qn("map", "er2db_entity", "customer_map"),
                    entity = qn("er", "entity", "customer"),
                    target = MappingTarget.Table(qn("db", "dbo", "customers")),
                )
            val def = ModelToDefinitions.er2dbEntityMappingToDef(mapping)
            def.name shouldBe "customer_map"
            def.entity?.path shouldBe "er.entity.customer"
            val tableEntry = (def.target as? TargetObjectValue)?.obj?.entries?.get("table")
            tableEntry shouldNotBe null
            (tableEntry as? PropertyValue.IdValue)?.ref?.path shouldBe "db.dbo.customers"
        }

        "er2dbEntityMappingToDef converts Er2DbEntityMapping with SqlQuery target" {
            val mapping =
                Er2DbEntityMapping(
                    internalId = "m1",
                    qname = qn("map", "er2db_entity", "filtered_sales"),
                    entity = qn("er", "entity", "sales"),
                    target = MappingTarget.SqlQuery(qn("query", "query", "sales_filter")),
                )
            val def = ModelToDefinitions.er2dbEntityMappingToDef(mapping)
            val sqlEntry = (def.target as? TargetObjectValue)?.obj?.entries?.get("sqlQuery")
            sqlEntry shouldNotBe null
            (sqlEntry as? PropertyValue.IdValue)?.ref?.path shouldBe "query.query.sales_filter"
        }

        "er2dbAttributeMappingToDef converts Er2DbAttributeMapping with Column target" {
            val mapping =
                Er2DbAttributeMapping(
                    internalId = "m1",
                    qname = qn("map", "er2db_attribute", "customer_name_attr"),
                    attribute = qn("er", "entity", "customer.name"),
                    target =
                        AttributeMappingTarget
                            .Column(qn("db", "dbo", "customers.name")),
                )
            val def = ModelToDefinitions.er2dbAttributeMappingToDef(mapping)
            def.name shouldBe "customer_name_attr"
            def.attribute?.path shouldBe "er.entity.customer.name"
            val colEntry = (def.target as? TargetObjectValue)?.obj?.entries?.get("column")
            colEntry shouldNotBe null
            (colEntry as? PropertyValue.IdValue)?.ref?.path shouldBe "db.dbo.customers.name"
        }

        "er2dbRelationMappingToDef converts Er2DbRelationMapping" {
            val mapping =
                Er2DbRelationMapping(
                    internalId = "m1",
                    qname = qn("map", "er2db_relation", "orders_rel"),
                    relation = qn("er", "relation", "customer_orders"),
                    foreignKey = qn("db", "dbo", "fk_customer_orders"),
                )
            val def = ModelToDefinitions.er2dbRelationMappingToDef(mapping)
            def.name shouldBe "orders_rel"
            def.relation?.path shouldBe "er.relation.customer_orders"
            def.fk?.path shouldBe "db.dbo.fk_customer_orders"
        }

        "er2cncRoleMappingToDef converts Er2CncRoleMapping" {
            val mapping =
                Er2CncRoleMapping(
                    internalId = "m1",
                    qname = qn("map", "er2cnc_role", "sales_fact"),
                    entity = qn("er", "entity", "sales"),
                    role = qn("cnc", "role", "fact"),
                )
            val def = ModelToDefinitions.er2cncRoleMappingToDef(mapping)
            def.name shouldBe "sales_fact"
            def.entity?.path shouldBe "er.entity.sales"
            def.role?.path shouldBe "cnc.role.fact"
        }

        "relationToRelationDef converts Relation with join pairs" {
            val relation =
                Relation(
                    internalId = "r1",
                    qname = qn("er", "relation", "customer_orders"),
                    fromEntity = qn("er", "entity", "customer"),
                    toEntity = qn("er", "entity", "order"),
                    cardinality = Cardinality(0, -1, 0, 1),
                    joinPairs =
                        listOf(
                            AttributeJoinPair(
                                fromAttr = qn("er", "entity", "customer.id"),
                                toAttr = qn("er", "entity", "order.customer_id"),
                            ),
                        ),
                )
            val def = ModelToDefinitions.relationToRelationDef(relation)
            def.name shouldBe "customer_orders"
            def.from?.let {
                (it as? PropertyValue.IdValue)?.ref?.path shouldBe "er.entity.customer"
            }
            def.to?.let {
                (it as? PropertyValue.IdValue)?.ref?.path shouldBe "er.entity.order"
            }
            def.join.size shouldBe 1
        }

        "queryToQueryDef converts Query with parameters" {
            val query =
                Query(
                    internalId = "q1",
                    qname = qn("query", "query", "find_customers"),
                    description = "Find customers by name",
                    sourceLanguage = "SQL",
                    sourceText = "SELECT * FROM customers WHERE name LIKE @name",
                    parameters =
                        listOf(
                            QueryParameterDef(name = "name", type = "text", label = "Name pattern"),
                        ),
                )
            val def = ModelToDefinitions.queryToQueryDef(query)
            def.name shouldBe "find_customers"
            def.language shouldBe "SQL"
            def.parameters.size shouldBe 1
            val param0 = def.parameters[0] as PropertyValue.ObjectValue
            (param0.entries["name"] as? PropertyValue.IdValue)?.ref?.path shouldBe "name"
            (param0.entries["type"] as? PropertyValue.IdValue)?.ref?.path shouldBe "text"
            (param0.entries["label"] as? PropertyValue.StringValue)?.raw shouldBe "Name pattern"
        }

        "er2dbEntityMappingToDef handles null description" {
            val mapping =
                Er2DbEntityMapping(
                    internalId = "m1",
                    qname = qn("map", "er2db_entity", "simple"),
                    entity = qn("er", "entity", "x"),
                    target = MappingTarget.Table(qn("db", "dbo", "t")),
                )
            val def = ModelToDefinitions.er2dbEntityMappingToDef(mapping)
            def.description shouldBe null
        }

        "tableToTableDef maps varchar type to text" {
            val table =
                DbTable(
                    internalId = "t1",
                    qname = qn("db", "dbo", "test"),
                    columns =
                        listOf(
                            DbColumn(
                                internalId = "c1",
                                qname = qn("db", "dbo", "test.name"),
                                dataType = "varchar(50)",
                                table = qn("db", "dbo", "test"),
                            ),
                        ),
                )
            val def = ModelToDefinitions.tableToTableDef(table)
            def.columns[0].type?.name shouldBe "text"
        }

        "tableToTableDef maps int type correctly" {
            val table =
                DbTable(
                    internalId = "t1",
                    qname = qn("db", "dbo", "test"),
                    columns =
                        listOf(
                            DbColumn(
                                internalId = "c1",
                                qname = qn("db", "dbo", "test.count"),
                                dataType = "int",
                                table = qn("db", "dbo", "test"),
                            ),
                        ),
                )
            val def = ModelToDefinitions.tableToTableDef(table)
            def.columns[0].type?.name shouldBe "int"
        }

        "er2dbEntityMappingToDef handles View target" {
            val mapping =
                Er2DbEntityMapping(
                    internalId = "m1",
                    qname = qn("map", "er2db_entity", "v_map"),
                    entity = qn("er", "entity", "v"),
                    target = MappingTarget.View(qn("db", "dbo", "my_view")),
                )
            val def = ModelToDefinitions.er2dbEntityMappingToDef(mapping)
            val viewEntry = (def.target as? TargetObjectValue)?.obj?.entries?.get("view")
            viewEntry shouldNotBe null
            (viewEntry as? PropertyValue.IdValue)?.ref?.path shouldBe "db.dbo.my_view"
        }

        "er2dbAttributeMappingToDef handles Expression target" {
            val mapping =
                Er2DbAttributeMapping(
                    internalId = "m1",
                    qname = qn("map", "er2db_attribute", "computed"),
                    attribute = qn("er", "entity", "x.full_name"),
                    target =
                        AttributeMappingTarget.Expression(
                            "CONCAT(first_name, ' ', last_name)",
                        ),
                )
            val def = ModelToDefinitions.er2dbAttributeMappingToDef(mapping)
            val exprEntry = (def.target as? TargetObjectValue)?.obj?.entries?.get("expression")
            exprEntry shouldNotBe null
            (exprEntry as? PropertyValue.StringValue)?.raw shouldBe "CONCAT(first_name, ' ', last_name)"
        }

        "attribute searchable round-trips to a search block" {
            val attrQn = qn("er", "entity", "customer.name")
            val entityQn = qn("er", "entity", "customer")
            val tableQn = qn("db", "dbo", "customers")
            val colQn = qn("db", "dbo", "customers.id")
            val mappingQn = qn("map", "er2db_entity", "customer")
            val attrMappingQn = qn("map", "er2db_attribute", "customer.name")

            val col =
                DbColumn(
                    internalId = "c1",
                    qname = colQn,
                    table = tableQn,
                    dataType = "text",
                    isPrimaryKey = false,
                )
            val table =
                DbTable(
                    internalId = "t1",
                    qname = tableQn,
                    primaryKey = emptyList(),
                    columns = listOf(col),
                )
            val attr =
                Attribute(
                    internalId = "a1",
                    qname = attrQn,
                    entity = entityQn,
                    type = "text",
                    isKey = false,
                    nullable = true,
                    displayLabel = LocalizedText.EMPTY,
                    valueLabels = emptyMap(),
                    search = SearchHints(searchable = true),
                )
            val entity =
                Entity(
                    internalId = "e1",
                    qname = entityQn,
                    attributes = listOf(attr),
                    displayLabel = LocalizedText.EMPTY,
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
                        AttributeMappingTarget
                            .Column(colQn),
                )
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "test", name = "Test", description = ""),
                    version = ModelVersion(value = "v-test", swappedAt = Instant.now()),
                    schemas =
                        mapOf(
                            "er" to ErSchema(entities = mapOf(entityQn to entity)),
                            "db" to DbSchema(tables = mapOf(tableQn to table)),
                        ),
                    mappings = listOf(entityMapping, attrMapping),
                    queries = emptyMap(),
                )
            val bundle = ModelToDefinitions.convert(model)
            val erFile = bundle.files.first { it.filename == "er.ttr" }
            val content = TtrRenderer.renderFile(erFile.schemaCode, erFile.namespace, erFile.definitions)
            content shouldContain "searchable: true"
            val result = TtrLoader.parseString(content, fileLabel = "er.ttr")
            result.ok shouldBe true
        }

        "convert packages files by source file (NNnn_ prefix stripped) and emits no imports" {
            val src = "bt01_zakaznik.yaml" // package = "zakaznik"
            val entityQn = qn("er", "entity", "customer")
            val tableQn = qn("db", "dbo", "customers")
            val attrQn = qn("er", "entity", "customer.name")
            val colQn = qn("db", "dbo", "customers.id")
            val mappingQn = qn("map", "er2db_entity", "customer")
            val attrMappingQn = qn("map", "er2db_attribute", "customer.name")
            val col =
                DbColumn(
                    internalId = "c1",
                    qname = colQn,
                    table = tableQn,
                    dataType = "text",
                    isPrimaryKey = true,
                )
            val table =
                DbTable(
                    internalId = "t1",
                    qname = tableQn,
                    primaryKey = emptyList(),
                    columns = listOf(col),
                    sourceFile = src,
                )
            val attr =
                Attribute(
                    internalId = "a1",
                    qname = attrQn,
                    entity = entityQn,
                    type = "text",
                    isKey = false,
                    nullable = true,
                    displayLabel = LocalizedText.EMPTY,
                    valueLabels = emptyMap(),
                    search = SearchHints.EMPTY,
                )
            val entity =
                Entity(
                    internalId = "e1",
                    qname = entityQn,
                    attributes = listOf(attr),
                    displayLabel = LocalizedText.EMPTY,
                    sourceFile = src,
                )
            val entityMapping =
                Er2DbEntityMapping(
                    internalId = "m1",
                    qname = mappingQn,
                    entity = entityQn,
                    target = MappingTarget.Table(tableQn),
                    sourceFile = src,
                )
            val attrMapping =
                Er2DbAttributeMapping(
                    internalId = "m2",
                    qname = attrMappingQn,
                    attribute = attrQn,
                    target = AttributeMappingTarget.Column(colQn),
                    sourceFile = src,
                )
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "test", name = "Test", description = ""),
                    version = ModelVersion(value = "v-test", swappedAt = Instant.now()),
                    schemas =
                        mapOf(
                            "er" to ErSchema(entities = mapOf(entityQn to entity)),
                            "db" to DbSchema(tables = mapOf(tableQn to table)),
                        ),
                    mappings = listOf(entityMapping, attrMapping),
                    queries = emptyMap(),
                )
            val bundle = ModelToDefinitions.convert(model)
            bundle.files.isNotEmpty() shouldBe true
            val erFile = bundle.files.first { it.filename == "er.ttr" }
            val dbFile = bundle.files.first { it.filename == "db.ttr" }
            // Package name comes from the source file (NNnn_ prefix stripped), not the schema.
            erFile.packageName shouldBe "zakaznik"
            dbFile.packageName shouldBe "zakaznik"
            // v2.1: er2db mappings are inline ⇒ no standalone map.ttr for this fixture.
            bundle.files.none { it.filename == "map.ttr" } shouldBe true
            // References are fully-qualified, so no imports are emitted.
            erFile.imports shouldBe emptyList()
            dbFile.imports shouldBe emptyList()
        }

        "convert round-trip: render -> re-parse preserves package and imports" {
            val entityQn = qn("er", "entity", "customer")
            val tableQn = qn("db", "dbo", "customers")
            val colQn = qn("db", "dbo", "customers.id")
            val mappingQn = qn("map", "er2db_entity", "customer")
            val attrMappingQn = qn("map", "er2db_attribute", "customer.name")
            val col =
                DbColumn(
                    internalId = "c1",
                    qname = colQn,
                    table = tableQn,
                    dataType = "text",
                    isPrimaryKey = true,
                )
            val table =
                DbTable(
                    internalId = "t1",
                    qname = tableQn,
                    primaryKey = emptyList(),
                    columns = listOf(col),
                )
            val attrQn = qn("er", "entity", "customer.name")
            val attr =
                Attribute(
                    internalId = "a1",
                    qname = attrQn,
                    entity = entityQn,
                    type = "text",
                    isKey = false,
                    nullable = true,
                    displayLabel = LocalizedText.EMPTY,
                    valueLabels = emptyMap(),
                    search = SearchHints.EMPTY,
                )
            val entity =
                Entity(
                    internalId = "e1",
                    qname = entityQn,
                    attributes = listOf(attr),
                    displayLabel = LocalizedText.EMPTY,
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
                    target = AttributeMappingTarget.Column(colQn),
                )
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "test", name = "Test", description = ""),
                    version = ModelVersion(value = "v-test", swappedAt = Instant.now()),
                    schemas =
                        mapOf(
                            "er" to ErSchema(entities = mapOf(entityQn to entity)),
                            "db" to DbSchema(tables = mapOf(tableQn to table)),
                        ),
                    mappings = listOf(entityMapping, attrMapping),
                    queries = emptyMap(),
                )
            val bundle = ModelToDefinitions.convert(model)
            for (file in bundle.files) {
                val rendered =
                    TtrRenderer.renderFile(
                        file.schemaCode,
                        file.namespace,
                        file.definitions,
                        file.packageName,
                        file.imports,
                    )
                val parseResult = TtrLoader.parseString(rendered, fileLabel = file.filename)
                parseResult.ok shouldBe true
                parseResult.packageName shouldBe file.packageName
                parseResult.imports.isEmpty() shouldBe (file.imports.isEmpty())
            }
        }
    })
