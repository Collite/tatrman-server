package org.tatrman.kantheon.ariadne.export

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.parseSchemaCode
import org.tatrman.kantheon.ariadne.model.Attribute
import org.tatrman.kantheon.ariadne.model.CncSchema
import org.tatrman.kantheon.ariadne.model.DbColumn
import org.tatrman.kantheon.ariadne.model.DbSchema
import org.tatrman.kantheon.ariadne.model.DbTable
import org.tatrman.kantheon.ariadne.model.Entity
import org.tatrman.kantheon.ariadne.model.Er2CncRoleMapping
import org.tatrman.kantheon.ariadne.model.Er2DbEntityMapping
import org.tatrman.kantheon.ariadne.model.ErSchema
import org.tatrman.kantheon.ariadne.model.LocalizedText
import org.tatrman.kantheon.ariadne.model.MappingTarget
import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.model.ModelVersion
import org.tatrman.kantheon.ariadne.model.Role
import org.tatrman.kantheon.ariadne.model.SearchHints
import org.tatrman.kantheon.ariadne.reconcile.ModelReconciler
import org.tatrman.kantheon.ariadne.source.BuiltinStockSource
import org.tatrman.kantheon.ariadne.source.FileBasedSource
import org.tatrman.kantheon.ariadne.source.LocalFsStorage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttr.writer.TtrRenderer
import java.nio.file.Files
import java.time.Instant

/**
 * End-to-end round-trip for the per-source-file packaging: a model whose objects all
 * come from one source file (`bt01_obchod.yaml` → package `obchod`) AND that contains a
 * deliberate entity/table name collision (`order` entity vs `order` table) is converted,
 * written to its package-named subdirectory, and reloaded through `FileBasedSource` +
 * `ModelReconciler` (+ `BuiltinStockSource` for stock roles). Proves the package layout
 * round-trips and that fully-qualified refs resolve exactly despite the bare-name clash.
 */
class ExportRoundTripSpec :
    StringSpec({

        fun qn(
            schema: String,
            ns: String,
            name: String,
            pkg: String = "",
        ): QualifiedName =
            QualifiedName
                .newBuilder()
                .setPackage(pkg)
                .setSchemaCode(parseSchemaCode(schema) ?: SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                .setNamespace(ns)
                .setName(name)
                .build()

        // Everything comes from one source file → package "obchod"; entity `order` (er) and
        // table `order` (db) deliberately share a bare name.
        fun collidingModel(): Model {
            val src = "bt01_obchod.yaml"
            val entityQn = qn("er", "entity", "order")
            val attrQn = qn("er", "entity", "order.id")
            val tableQn = qn("db", "dbo", "order")
            val colQn = qn("db", "dbo", "order.id")
            val roleQn = qn("cnc", "role", "fact", pkg = "cnc")

            val entity =
                Entity(
                    internalId = "e1",
                    qname = entityQn,
                    attributes =
                        listOf(
                            Attribute(
                                internalId = "a1",
                                qname = attrQn,
                                entity = entityQn,
                                type = "int",
                                isKey = true,
                                displayLabel = LocalizedText(emptyMap()),
                            ),
                        ),
                    displayLabel = LocalizedText(emptyMap()),
                    sourceFile = src,
                )
            val table =
                DbTable(
                    internalId = "t1",
                    qname = tableQn,
                    primaryKey = listOf("id"),
                    columns =
                        listOf(
                            DbColumn(
                                internalId = "c1",
                                qname = colQn,
                                table = tableQn,
                                dataType = "int",
                                isPrimaryKey = true,
                            ),
                        ),
                    sourceFile = src,
                )
            val userRole =
                Role(
                    internalId = "r1",
                    qname = qn("cnc", "role", "custom", pkg = "cnc"),
                    label = LocalizedText(mapOf("en" to "Custom")),
                    sourceFile = src,
                    search = SearchHints.EMPTY,
                )
            val entityMapping =
                Er2DbEntityMapping(
                    internalId = "m1",
                    qname = qn("map", "er2db_entity", "order"),
                    entity = entityQn,
                    target = MappingTarget.Table(tableQn),
                    sourceFile = src,
                )
            val roleMapping =
                Er2CncRoleMapping(
                    internalId = "m2",
                    qname = qn("map", "er2cnc_role", "order__fact"),
                    entity = entityQn,
                    role = roleQn,
                    sourceFile = src,
                )

            return Model(
                descriptor = ModelDescriptor(id = "rt", name = "rt", description = ""),
                version = ModelVersion(value = "v1", swappedAt = Instant.now()),
                schemas =
                    mapOf(
                        "er" to ErSchema(entities = mapOf(entityQn to entity)),
                        "db" to DbSchema(tables = mapOf(tableQn to table)),
                        "cnc" to CncSchema(roles = mapOf(userRole.qname to userRole)),
                    ),
                mappings = listOf(entityMapping, roleMapping),
                queries = emptyMap(),
            )
        }

        "per-file packaging reloads with no resolution errors (incl. entity/table name collision)" {
            val bundle = ModelToDefinitions.convert(collidingModel())
            val root = Files.createTempDirectory("export-rt")
            for (file in bundle.files) {
                val content =
                    TtrRenderer.renderFile(
                        file.schemaCode,
                        file.namespace,
                        file.definitions,
                        file.packageName,
                        file.imports,
                    )
                val dir = if (file.packageName != null) root.resolve(file.packageName) else root
                Files.createDirectories(dir)
                Files.writeString(dir.resolve(file.filename), content)
            }

            // Everything landed under the package-named directory and declares `package obchod`.
            Files.readString(root.resolve("obchod").resolve("er.ttr")) shouldContain "package obchod"
            Files.readString(root.resolve("obchod").resolve("cnc.ttr")) shouldContain "package obchod"

            val source =
                FileBasedSource(
                    sourceId = "rt",
                    priority = 100,
                    storage = LocalFsStorage(id = "rt", rootPath = root),
                )
            val result =
                ModelReconciler(ModelDescriptor(id = "rt", name = "rt"))
                    .reconcile(listOf(BuiltinStockSource().load(), source.load()))

            // FQ refs (er.entity.order, db.dbo.order, cnc.cnc.role.fact) resolve exactly — no
            // false ambiguity from the order/order clash, no unimported, no mismatch.
            result.errors.filter { it.message.contains("ambiguous-reference") } shouldHaveSize 0
            result.errors.filter { it.message.contains("unimported-reference") } shouldHaveSize 0
            result.errors shouldHaveSize 0
        }
    })
