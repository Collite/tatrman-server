package org.tatrman.kantheon.ariadne.export

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.parseSchemaCode
import org.tatrman.kantheon.ariadne.model.Attribute
import org.tatrman.kantheon.ariadne.model.Cardinality
import org.tatrman.kantheon.ariadne.model.CncSchema
import org.tatrman.kantheon.ariadne.model.DbColumn
import org.tatrman.kantheon.ariadne.model.DbForeignKey
import org.tatrman.kantheon.ariadne.model.DbProcedure
import org.tatrman.kantheon.ariadne.model.DbSchema
import org.tatrman.kantheon.ariadne.model.DbTable
import org.tatrman.kantheon.ariadne.model.DbView
import org.tatrman.kantheon.ariadne.model.Entity
import org.tatrman.kantheon.ariadne.model.Er2CncRoleMapping
import org.tatrman.kantheon.ariadne.model.ErSchema
import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.model.ModelVersion
import org.tatrman.kantheon.ariadne.model.Query
import org.tatrman.kantheon.ariadne.model.Relation
import org.tatrman.kantheon.ariadne.model.Role
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.writer.TtrRenderer
import java.time.Instant

/**
 * Stage 3 (yaml-converter-inline-split) — per package the converter emits at most
 * `db.ttr` (tables/views/fk/procedures/queries), `er.ttr` (entities + relations),
 * and `cnc.ttr` (roles + er2cnc_role) — and **no** schema/namespace directive in
 * any of them (each def derives its schema+namespace from its kind).
 */
class DbErCncSplitSpec :
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

        // One source file ⇒ one package "navsteva"; covers every bucket-relevant kind.
        fun model(): Model {
            val src = "bt09_navsteva.yaml"
            val entityQn = qn("er", "entity", "customer")
            val attrQn = qn("er", "entity", "customer.id")
            val relQn = qn("er", "relation", "customer_self")
            val tableQn = qn("db", "dbo", "customers")
            val colQn = qn("db", "dbo", "customers.id")
            val viewQn = qn("db", "dbo", "v_customers")
            val procQn = qn("db", "dbo", "sp_customers")
            val fkQn = qn("db", "dbo", "fk_customers")
            val queryQn = qn("query", "query", "all_customers")
            val roleQn = qn("cnc", "role", "fact")

            val col = DbColumn(internalId = "c1", qname = colQn, table = tableQn, dataType = "int", isPrimaryKey = true)
            val table = DbTable(internalId = "t1", qname = tableQn, columns = listOf(col), sourceFile = src)
            val view = DbView(internalId = "v1", qname = viewQn, columns = listOf(col), sourceFile = src)
            val proc = DbProcedure(internalId = "p1", qname = procQn, sourceFile = src)
            val fk =
                DbForeignKey(
                    internalId = "fk1",
                    qname = fkQn,
                    fromColumns = listOf(colQn),
                    toColumns = listOf(colQn),
                    sourceFile = src,
                )
            val attr = Attribute(internalId = "a1", qname = attrQn, entity = entityQn, type = "int", isKey = true)
            val entity = Entity(internalId = "e1", qname = entityQn, attributes = listOf(attr), sourceFile = src)
            val rel =
                Relation(
                    internalId = "r1",
                    qname = relQn,
                    fromEntity = entityQn,
                    toEntity = entityQn,
                    cardinality = Cardinality(0, -1, 0, 1),
                    sourceFile = src,
                )
            val query =
                Query(
                    internalId = "q1",
                    qname = queryQn,
                    sourceLanguage = "SQL",
                    sourceText = "SELECT 1",
                    sourceFile = src,
                )
            val role = Role(internalId = "ro1", qname = roleQn, sourceFile = src)
            val er2cnc =
                Er2CncRoleMapping(
                    internalId = "m1",
                    qname = qn("map", "er2cnc_role", "customer__fact"),
                    entity = entityQn,
                    role = roleQn,
                    sourceFile = src,
                )

            return Model(
                descriptor = ModelDescriptor(id = "t", name = "T", description = ""),
                version = ModelVersion(value = "v", swappedAt = Instant.EPOCH),
                schemas =
                    mapOf(
                        "er" to ErSchema(entities = mapOf(entityQn to entity), relations = mapOf(relQn to rel)),
                        "db" to
                            DbSchema(
                                tables = mapOf(tableQn to table),
                                views = mapOf(viewQn to view),
                                procedures = mapOf(procQn to proc),
                                foreignKeys = mapOf(fkQn to fk),
                            ),
                        "cnc" to CncSchema(roles = mapOf(roleQn to role)),
                    ),
                mappings = listOf(er2cnc),
                queries = mapOf(queryQn to query),
            )
        }

        "3.1 per package the bundle has exactly db.ttr, er.ttr, cnc.ttr" {
            val bundle = ModelToDefinitions.convert(model())
            val pkgFiles = bundle.files.filter { it.packageName == "navsteva" }.map { it.filename }
            pkgFiles shouldContainExactlyInAnyOrder listOf("db.ttr", "er.ttr", "cnc.ttr")
            pkgFiles shouldNotContain "map.ttr"
            pkgFiles shouldNotContain "relation.ttr"
            pkgFiles shouldNotContain "query.ttr"
        }

        "3.2 bucket membership: db<-tables/views/fk/procedures/queries; er<-entities+relations; cnc<-roles+er2cnc" {
            val bundle = ModelToDefinitions.convert(model())

            fun render(name: String): String {
                val f = bundle.files.first { it.filename == name && it.packageName == "navsteva" }
                return TtrRenderer.renderFile(f.schemaCode, f.namespace, f.definitions, f.packageName, f.imports)
            }

            val db = render("db.ttr")
            listOf(
                "def table customers",
                "def view v_customers",
                "def procedure sp_customers",
                "def fk fk_customers",
                "def query all_customers",
            ).forEach { db.contains(it) shouldBe true }

            val er = render("er.ttr")
            er.contains("def entity customer") shouldBe true
            er.contains("def relation customer_self") shouldBe true

            val cnc = render("cnc.ttr")
            cnc.contains("def role fact") shouldBe true
            cnc.contains("def er2cnc_role customer__fact") shouldBe true
        }

        "3.3 db/er/cnc files carry no schema/namespace directive and parse cleanly" {
            val bundle = ModelToDefinitions.convert(model())
            for (name in listOf("db.ttr", "er.ttr", "cnc.ttr")) {
                val f = bundle.files.first { it.filename == name && it.packageName == "navsteva" }
                f.schemaCode shouldBe null
                f.namespace shouldBe null
                val content = TtrRenderer.renderFile(f.schemaCode, f.namespace, f.definitions, f.packageName, f.imports)
                // No leading `schema …` line (the package line may lead instead).
                content.lineSequence().none { it.trimStart().startsWith("schema ") } shouldBe true
                TtrLoader.parseString(content, fileLabel = name).ok shouldBe true
            }
        }
    })
