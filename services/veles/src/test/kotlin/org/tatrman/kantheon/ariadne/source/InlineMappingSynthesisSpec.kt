package org.tatrman.kantheon.ariadne.source

import org.tatrman.kantheon.ariadne.model.Er2DbAttributeMapping
import org.tatrman.kantheon.ariadne.model.Er2DbEntityMapping
import org.tatrman.kantheon.ariadne.model.Er2DbRelationMapping
import org.tatrman.kantheon.ariadne.model.MappingSource
import org.tatrman.kantheon.ariadne.model.MappingTarget
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.reconcile.ModelReconciler
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

/**
 * v2.1 — synthesiser + duplicate-mapping validator tests.
 *
 *  - Synthesiser: an entity / attribute / relation with an inline `mapping:`
 *    block contributes Er2Db*Mapping records tagged with `MappingSource.Inline`.
 *  - Validator: when an inline mapping and an explicit `def er2db_*` declaration
 *    target the same qname, `ttr/duplicate-mapping` is emitted.
 */
class InlineMappingSynthesisSpec :
    StringSpec({

        // Minimal in-memory ModelStorage so we can drive FileBasedSource without
        // touching disk. Keys are file paths; values are file contents.
        class InMemoryStorage(
            override val id: String,
            private val files: Map<String, String>,
        ) : ModelStorage {
            override fun fetchVersion(): String = "test"

            override fun listFiles(
                extensions: List<String>,
                prefixes: List<String>,
            ): List<StorageFile> =
                files.keys
                    .filter { p -> extensions.any { p.endsWith(".$it") } }
                    .map { StorageFile(path = it, sizeBytes = 0L, rootPath = Path.of("/")) }

            override fun read(file: StorageFile): String = files[file.path] ?: ""
        }

        fun loadInline(files: Map<String, String>): org.tatrman.kantheon.ariadne.reconcile.ReconciliationResult {
            val source =
                FileBasedSource(
                    sourceId = "inline-test",
                    priority = 100,
                    storage = InMemoryStorage(id = "inline-test", files = files),
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "inline-test", name = "inline-test"))
            return reconciler.reconcile(listOf(source.load()))
        }

        "entity-level inline mapping synthesises Er2DbEntityMapping + per-column Er2DbAttributeMapping" {
            val erTtr =
                """
                schema er namespace entity
                def entity artikl {
                    binding: {
                        target: { table: db.dbo.QZBOZI_DF },
                        columns: {
                            id_artiklu: IDZBOZI,
                            kód_artiklu: { target: KOD_ZBOZI }
                        }
                    },
                    attributes: [
                        def attribute id_artiklu { type: int, isKey: true },
                        def attribute kód_artiklu { type: text }
                    ]
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr))
            val mappings = result.model.mappings

            val entityMapping = mappings.filterIsInstance<Er2DbEntityMapping>().single()
            entityMapping.mappingSource shouldBe MappingSource.Inline("entity")
            entityMapping.qname.name shouldBe "artikl"
            entityMapping.target.shouldBeInstanceOf<MappingTarget.Table>()

            val attrMappings = mappings.filterIsInstance<Er2DbAttributeMapping>()
            attrMappings shouldHaveSize 2
            attrMappings.all { it.mappingSource == MappingSource.Inline("entity") } shouldBe true
            attrMappings.map { it.qname.name }.toSet() shouldBe
                setOf("artikl.id_artiklu", "artikl.kód_artiklu")
        }

        "attribute-level inline mapping synthesises Er2DbAttributeMapping tagged Inline(\"attribute\")" {
            val erTtr =
                """
                schema er namespace entity
                def entity produkt {
                    attributes: [
                        def attribute id_produktu { type: int, isKey: true, binding: IDSKUPZBOZI },
                        def attribute kód_produktu { type: text, binding: { target: { column: KOD_SKUPZBOZI } } }
                    ]
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr))
            val attrMappings = result.model.mappings.filterIsInstance<Er2DbAttributeMapping>()
            attrMappings shouldHaveSize 2
            attrMappings.all { it.mappingSource == MappingSource.Inline("attribute") } shouldBe true
            val byName = attrMappings.associateBy { it.qname.name }
            byName["produkt.id_produktu"].shouldNotBeNull()
            byName["produkt.kód_produktu"].shouldNotBeNull()
        }

        "relation-level inline mapping (bare-fk) synthesises Er2DbRelationMapping tagged Inline" {
            val erTtr =
                """
                schema er namespace entity
                def entity a {}
                def entity b {}
                def relation artikl_produkt {
                    from: er.entity.a, to: er.entity.b,
                    cardinality: { from: "0..*", to: "0..1" },
                    join: [{ from: er.entity.a.x, to: er.entity.b.x }],
                    binding: db.dbo.fk_a_b
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr))
            val relMapping =
                result.model.mappings
                    .filterIsInstance<Er2DbRelationMapping>()
                    .single()
            relMapping.mappingSource shouldBe MappingSource.Inline("relation")
            relMapping.qname.name shouldBe "artikl_produkt"
            relMapping.foreignKey.name shouldBe "fk_a_b"
        }

        "relation-level inline mapping (wrapped fk) synthesises Er2DbRelationMapping tagged Inline" {
            val erTtr =
                """
                schema er namespace entity
                def entity a {}
                def entity b {}
                def relation r {
                    from: er.entity.a, to: er.entity.b,
                    cardinality: { from: "0..*", to: "0..1" },
                    join: [{ from: er.entity.a.x, to: er.entity.b.x }],
                    binding: { fk: db.dbo.fk_a_b }
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr))
            result.model.mappings.filterIsInstance<Er2DbRelationMapping>() shouldHaveSize 1
        }

        "entity without inline mapping contributes no Er2Db mappings" {
            val erTtr =
                """
                schema er namespace entity
                def entity plain {
                    attributes: [
                        def attribute id { type: int, isKey: true }
                    ]
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr))
            result.model.mappings.filterIsInstance<Er2DbEntityMapping>() shouldHaveSize 0
            result.model.mappings.filterIsInstance<Er2DbAttributeMapping>() shouldHaveSize 0
        }

        "ttr/duplicate-mapping fires when an inline entity mapping and an explicit def er2db_entity share a qname" {
            val erTtr =
                """
                schema er namespace entity
                def entity artikl {
                    binding: { target: { table: db.dbo.QZBOZI_DF } },
                    attributes: [ def attribute id { type: int, isKey: true } ]
                }
                """.trimIndent()
            val mapTtr =
                """
                schema binding
                def er2db_entity artikl { entity: er.entity.artikl, target: { table: db.dbo.OTHER } }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr, "/map.ttr" to mapTtr))
            val dup = result.errors.filter { it.message.contains("ttr/duplicate-mapping") }
            dup shouldHaveSize 2 // one per offending location
            dup[0].message shouldContain "artikl"
            dup[0].message shouldContain "inline on entity"
            dup[0].message shouldContain "explicit def er2db_*"
        }

        "ttr/duplicate-mapping fires when inline attribute mapping collides with explicit def er2db_attribute" {
            val erTtr =
                """
                schema er namespace entity
                def entity produkt {
                    attributes: [
                        def attribute id_produktu { type: int, isKey: true, binding: IDSKUPZBOZI }
                    ]
                }
                """.trimIndent()
            val mapTtr =
                """
                schema binding
                def er2db_attribute produkt.id_produktu {
                    attribute: er.entity.produkt.id_produktu,
                    target: { column: db.dbo.OTHER.IDSKUPZBOZI }
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr, "/map.ttr" to mapTtr))
            val dup = result.errors.filter { it.message.contains("ttr/duplicate-mapping") }
            dup shouldHaveSize 2
            dup[0].message shouldContain "produkt.id_produktu"
        }

        "ttr/duplicate-mapping fires when inline relation mapping collides with explicit def er2db_relation" {
            val erTtr =
                """
                schema er namespace entity
                def entity a {}
                def entity b {}
                def relation artikl_produkt {
                    from: er.entity.a, to: er.entity.b,
                    cardinality: { from: "0..*", to: "1" },
                    join: [{ from: er.entity.a.x, to: er.entity.b.x }],
                    binding: db.dbo.fk_one
                }
                """.trimIndent()
            val mapTtr =
                """
                schema binding
                def er2db_relation artikl_produkt {
                    relation: er.entity.artikl_produkt,
                    fk: db.dbo.fk_two
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr, "/map.ttr" to mapTtr))
            val dup = result.errors.filter { it.message.contains("ttr/duplicate-mapping") }
            dup shouldHaveSize 2
            dup[0].message shouldContain "artikl_produkt"
            dup[0].message shouldContain "inline on relation"
            dup[0].message shouldContain "explicit def er2db_*"
        }

        "relation-level inline mapping respects host file's ER namespace" {
            val erTtr =
                """
                schema er namespace foo
                def entity a {}
                def entity b {}
                def relation r {
                    from: er.foo.a, to: er.foo.b,
                    cardinality: { from: "0..*", to: "1" },
                    join: [{ from: er.foo.a.x, to: er.foo.b.x }],
                    binding: db.dbo.fk_a_b
                }
                """.trimIndent()

            val result = loadInline(mapOf("/er.ttr" to erTtr))
            val rel =
                result.model.mappings
                    .filterIsInstance<Er2DbRelationMapping>()
                    .single()
            rel.mappingSource shouldBe MappingSource.Inline("relation")
            rel.relation.namespace shouldBe "foo"
            rel.relation.name shouldBe "r"
        }

        "no duplicate-mapping when only explicit defs are present (pre-v2.1 behaviour preserved)" {
            val mapTtr =
                """
                schema binding
                def er2db_entity artikl { entity: er.entity.artikl, target: { table: db.dbo.QZBOZI_DF } }
                """.trimIndent()
            val result = loadInline(mapOf("/map.ttr" to mapTtr))
            result.errors.filter { it.message.contains("ttr/duplicate-mapping") } shouldHaveSize 0
        }
    })
