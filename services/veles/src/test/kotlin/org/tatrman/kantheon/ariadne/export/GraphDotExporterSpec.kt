package org.tatrman.kantheon.ariadne.export

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.parseSchemaCode
import org.tatrman.kantheon.ariadne.graph.ModelGraph
import org.tatrman.kantheon.ariadne.model.DbColumn
import org.tatrman.kantheon.ariadne.model.DbSchema
import org.tatrman.kantheon.ariadne.model.DbTable
import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.model.ModelVersion
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class GraphDotExporterSpec :
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

        fun smallModel(): Model {
            val customersQname = qn("db", "dbo", "customers")
            val idCol =
                DbColumn(
                    internalId = "col-customers-id",
                    qname = qn("db", "dbo", "customers.id"),
                    table = customersQname,
                    dataType = "bigint",
                    nullable = false,
                )
            val nameCol =
                DbColumn(
                    internalId = "col-customers-name",
                    qname = qn("db", "dbo", "customers.name"),
                    table = customersQname,
                    dataType = "varchar",
                    nullable = true,
                )
            val customers =
                DbTable(
                    internalId = "tbl-customers",
                    qname = customersQname,
                    columns = listOf(idCol, nameCol),
                )
            val schema = DbSchema(tables = mapOf(customersQname to customers))
            return Model(
                descriptor = ModelDescriptor(id = "test", name = "test"),
                version = ModelVersion("0", java.time.Instant.now()),
                schemas = mapOf("db" to schema),
                mappings = emptyList(),
                queries = emptyMap(),
            )
        }

        "export produces a digraph header and trailer" {
            val graph = ModelGraph.build(smallModel())
            val dot = GraphDotExporter.export(graph)
            dot.shouldContain("digraph metadata_model {")
            dot.trimEnd().endsWith("}") shouldBe true
        }

        "export includes the table vertex with kind tag in the label" {
            val graph = ModelGraph.build(smallModel())
            val dot = GraphDotExporter.export(graph)
            dot.shouldContain("[table]")
            dot.shouldContain("db.dbo.customers")
        }

        "export includes the column vertices and a DEFINES edge from column to table" {
            val graph = ModelGraph.build(smallModel())
            val dot = GraphDotExporter.export(graph)
            dot.shouldContain("[column]")
            // The DEFINES edge labels are in the edge attribute block.
            dot.shouldContain("label=\"DEFINES\"")
        }

        "export DOT-quotes identifiers and escapes embedded quotes / backslashes" {
            // Build a model with a deliberately ugly internal id so we can probe the quoting.
            val ugly = qn("db", "dbo", "weird\"name\\here")
            val col =
                DbColumn(
                    internalId = "col-id-with-\"quote\"-and-\\backslash",
                    qname = ugly,
                    table = ugly,
                    dataType = "varchar",
                    nullable = true,
                )
            val tbl =
                DbTable(
                    internalId = "tbl-id",
                    qname = ugly,
                    columns = listOf(col),
                )
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "test", name = "test"),
                    version = ModelVersion("0", java.time.Instant.now()),
                    schemas = mapOf("db" to DbSchema(tables = mapOf(ugly to tbl))),
                    mappings = emptyList(),
                    queries = emptyMap(),
                )
            val graph = ModelGraph.build(model)
            val dot = GraphDotExporter.export(graph)
            // Quoted form for the internal id ("...\"quote\"...\\backslash") must be present.
            dot.shouldContain("\\\"quote\\\"")
            dot.shouldContain("\\\\backslash")
        }
    })
