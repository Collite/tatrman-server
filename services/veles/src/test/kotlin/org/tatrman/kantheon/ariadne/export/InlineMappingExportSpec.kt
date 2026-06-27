package org.tatrman.kantheon.ariadne.export

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.parseSchemaCode
import org.tatrman.kantheon.ariadne.model.Attribute
import org.tatrman.kantheon.ariadne.model.AttributeMappingTarget
import org.tatrman.kantheon.ariadne.model.Cardinality
import org.tatrman.kantheon.ariadne.model.Entity
import org.tatrman.kantheon.ariadne.model.Er2DbAttributeMapping
import org.tatrman.kantheon.ariadne.model.Er2DbEntityMapping
import org.tatrman.kantheon.ariadne.model.Er2DbRelationMapping
import org.tatrman.kantheon.ariadne.model.ErSchema
import org.tatrman.kantheon.ariadne.model.MappingTarget
import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.model.ModelVersion
import org.tatrman.kantheon.ariadne.model.Relation
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.parser.model.BindingPropertyBareId
import org.tatrman.ttr.parser.model.BindingPropertyBlock
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.Er2DbAttributeDef
import org.tatrman.ttr.parser.model.Er2DbEntityDef
import org.tatrman.ttr.parser.model.Er2DbRelationDef
import org.tatrman.ttr.parser.model.RelationDef
import java.time.Instant

/**
 * Stage 2 (yaml-converter-inline-split) — `ModelToDefinitions.convert` attaches
 * er2db mappings **inline** on the owning entity / attribute / relation def
 * instead of emitting standalone `def er2db_*` blocks. `er2cnc_role` stays
 * standalone (covered elsewhere).
 */
class InlineMappingExportSpec :
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

        // ----- shared fixture: artikl (entity-level block) + produkt (per-attribute) + relation -----

        val artiklQn = qn("er", "entity", "artikl")
        val produktQn = qn("er", "entity", "produkt")
        val relQn = qn("er", "relation", "artikl_produkt")
        val fkQn = qn("db", "dbo", "fk_artikl_produkt")

        fun model(): Model {
            val idAttrQn = qn("er", "entity", "artikl.id_artiklu")
            val kodAttrQn = qn("er", "entity", "artikl.kód_artiklu")
            val tableQn = qn("db", "dbo", "QZBOZI_DF")
            val idColQn = qn("db", "dbo", "QZBOZI_DF.IDZBOZI")

            val idAttr = Attribute(internalId = "a1", qname = idAttrQn, entity = artiklQn, type = "int", isKey = true)
            val kodAttr = Attribute(internalId = "a2", qname = kodAttrQn, entity = artiklQn, type = "text")
            val artikl = Entity(internalId = "e1", qname = artiklQn, attributes = listOf(idAttr, kodAttr))

            // produkt: NO entity-level mapping ⇒ its attribute carries the inline mapping itself.
            val pidAttrQn = qn("er", "entity", "produkt.id_produktu")
            val pidColQn = qn("db", "dbo", "QSKUPZBOZI.IDSKUPZBOZI")
            val pidAttr =
                Attribute(internalId = "a3", qname = pidAttrQn, entity = produktQn, type = "int", isKey = true)
            val produkt = Entity(internalId = "e2", qname = produktQn, attributes = listOf(pidAttr))

            val rel =
                Relation(
                    internalId = "r1",
                    qname = relQn,
                    fromEntity = artiklQn,
                    toEntity = produktQn,
                    cardinality = Cardinality(0, -1, 0, 1),
                )

            val entityMapping =
                Er2DbEntityMapping(
                    internalId = "m1",
                    qname = qn("map", "er2db_entity", "artikl"),
                    entity = artiklQn,
                    target = MappingTarget.Table(tableQn),
                )
            // plain column ⇒ short bare-id in the columns map
            val idAttrMapping =
                Er2DbAttributeMapping(
                    internalId = "m2",
                    qname = qn("map", "er2db_attribute", "artikl.id_artiklu"),
                    attribute = idAttrQn,
                    target = AttributeMappingTarget.Column(idColQn),
                )
            // expression ⇒ object/block form in the columns map
            val kodAttrMapping =
                Er2DbAttributeMapping(
                    internalId = "m3",
                    qname = qn("map", "er2db_attribute", "artikl.kód_artiklu"),
                    attribute = kodAttrQn,
                    target = AttributeMappingTarget.Expression("UPPER(KOD_ZBOZI)"),
                )
            val pidAttrMapping =
                Er2DbAttributeMapping(
                    internalId = "m4",
                    qname = qn("map", "er2db_attribute", "produkt.id_produktu"),
                    attribute = pidAttrQn,
                    target = AttributeMappingTarget.Column(pidColQn),
                )
            val relMapping =
                Er2DbRelationMapping(
                    internalId = "m5",
                    qname = qn("map", "er2db_relation", "artikl_produkt"),
                    relation = relQn,
                    foreignKey = fkQn,
                )

            return Model(
                descriptor = ModelDescriptor(id = "t", name = "T", description = ""),
                version = ModelVersion(value = "v", swappedAt = Instant.EPOCH),
                schemas =
                    mapOf(
                        "er" to
                            ErSchema(
                                entities = mapOf(artiklQn to artikl, produktQn to produkt),
                                relations = mapOf(relQn to rel),
                            ),
                    ),
                mappings = listOf(entityMapping, idAttrMapping, kodAttrMapping, pidAttrMapping, relMapping),
                queries = emptyMap(),
            )
        }

        // ----- 2.1 — mappings are inline; no standalone er2db defs; no map.ttr -----

        "2.1 no standalone er2db_* defs are emitted" {
            val bundle = ModelToDefinitions.convert(model())
            val defs = bundle.files.flatMap { it.definitions }
            defs.none { it is Er2DbEntityDef } shouldBe true
            defs.none { it is Er2DbAttributeDef } shouldBe true
            defs.none { it is Er2DbRelationDef } shouldBe true
            // With only er2db mappings (no er2cnc), the `map` bucket is now empty.
            bundle.files.none { it.filename == "map.ttr" } shouldBe true
        }

        "2.1 entity/attribute/relation defs carry the inline mapping" {
            val defs = ModelToDefinitions.convert(model()).files.flatMap { it.definitions }
            val artikl = defs.filterIsInstance<EntityDef>().first { it.name == "artikl" }
            artikl.binding.shouldBeInstanceOf<BindingPropertyBlock>()
            val rel = defs.filterIsInstance<RelationDef>().first { it.name == "artikl_produkt" }
            rel.binding.shouldBeInstanceOf<BindingPropertyBareId>()
        }

        // ----- 2.2 — short vs block selection -----

        "2.2 entity-level block carries only target, no columns map" {
            val defs = ModelToDefinitions.convert(model()).files.flatMap { it.definitions }
            val artikl = defs.filterIsInstance<EntityDef>().first { it.name == "artikl" }
            val block = artikl.binding as BindingPropertyBlock
            block.columns.shouldBeEmpty()
        }

        "2.2 per-attribute mapping even under an entity-level block: plain ⇒ bare-id, expression ⇒ block" {
            val defs = ModelToDefinitions.convert(model()).files.flatMap { it.definitions }
            val artikl = defs.filterIsInstance<EntityDef>().first { it.name == "artikl" }
            val byName = artikl.attributes.associateBy { it.name }
            // plain column ⇒ short bare-id
            byName.getValue("id_artiklu").binding.shouldBeInstanceOf<BindingPropertyBareId>()
            (byName.getValue("id_artiklu").binding as BindingPropertyBareId).id.path shouldBe "IDZBOZI"
            // expression ⇒ block form
            byName.getValue("kód_artiklu").binding.shouldBeInstanceOf<BindingPropertyBlock>()
        }

        "2.2 no entity-level block ⇒ per-attribute short mapping (produkt case)" {
            val defs = ModelToDefinitions.convert(model()).files.flatMap { it.definitions }
            val produkt = defs.filterIsInstance<EntityDef>().first { it.name == "produkt" }
            produkt.binding.shouldBeNull()
            val pid = produkt.attributes.first { it.name == "id_produktu" }
            pid.binding.shouldBeInstanceOf<BindingPropertyBareId>()
            (pid.binding as BindingPropertyBareId).id.path shouldBe "IDSKUPZBOZI"
        }

        "2.2 relation ⇒ bare-id fk reference (fully-qualified)" {
            val defs = ModelToDefinitions.convert(model()).files.flatMap { it.definitions }
            val rel = defs.filterIsInstance<RelationDef>().first { it.name == "artikl_produkt" }
            (rel.binding as BindingPropertyBareId).id.path shouldBe "db.dbo.fk_artikl_produkt"
        }
    })
