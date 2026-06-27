package org.tatrman.kantheon.ariadne.source

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.parseSchemaCode
import org.tatrman.kantheon.ariadne.export.ModelToDefinitions
import org.tatrman.kantheon.ariadne.model.Attribute
import org.tatrman.kantheon.ariadne.model.AttributeMappingTarget
import org.tatrman.kantheon.ariadne.model.Cardinality
import org.tatrman.kantheon.ariadne.model.Entity
import org.tatrman.kantheon.ariadne.model.Er2DbAttributeMapping
import org.tatrman.kantheon.ariadne.model.Er2DbEntityMapping
import org.tatrman.kantheon.ariadne.model.Er2DbRelationMapping
import org.tatrman.kantheon.ariadne.model.ErSchema
import org.tatrman.kantheon.ariadne.model.Mapping
import org.tatrman.kantheon.ariadne.model.MappingTarget
import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.model.ModelVersion
import org.tatrman.kantheon.ariadne.model.Relation
import org.tatrman.kantheon.ariadne.reconcile.ModelReconciler
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttr.writer.TtrRenderer
import java.nio.file.Path
import java.time.Instant

/**
 * Stage 2.7 — semantic equivalence: loading the **new inline-mapping** export
 * through the reconciler/synthesiser yields the **same set of mapping identities**
 * (kind + qname) as loading the **old standalone `def er2db_*`** rendering of the
 * same model. Inline and standalone must resolve to identical mapping qnames.
 */
class InlineMappingEquivalenceSpec :
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

        fun load(files: Map<String, String>): List<Mapping> {
            val source =
                FileBasedSource(
                    sourceId = "equiv-test",
                    priority = 100,
                    storage = InMemoryStorage(id = "equiv-test", files = files),
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "equiv-test", name = "equiv-test"))
            return reconciler.reconcile(listOf(source.load())).model.mappings
        }

        /** Mapping identity = kind + own qname name; order-independent. */
        fun ids(mappings: List<Mapping>): Set<String> = mappings.map { "${it.kind}:${it.qname.name}" }.toSet()

        "inline export and standalone er2db export synthesise the same mapping qnames" {
            // ----- shared model: entity (table mapping + 2 column mappings) + relation (fk) -----
            val artiklQn = qn("er", "entity", "artikl")
            val bQn = qn("er", "entity", "b")
            val idAttrQn = qn("er", "entity", "artikl.id_artiklu")
            val kodAttrQn = qn("er", "entity", "artikl.kód_artiklu")
            val relQn = qn("er", "relation", "artikl_b")
            val tableQn = qn("db", "dbo", "QZBOZI_DF")
            val idColQn = qn("db", "dbo", "QZBOZI_DF.IDZBOZI")
            val kodColQn = qn("db", "dbo", "QZBOZI_DF.KOD_ZBOZI")
            val fkQn = qn("db", "dbo", "fk_artikl_b")

            val idAttr = Attribute(internalId = "a1", qname = idAttrQn, entity = artiklQn, type = "int", isKey = true)
            val kodAttr = Attribute(internalId = "a2", qname = kodAttrQn, entity = artiklQn, type = "text")
            val artikl = Entity(internalId = "e1", qname = artiklQn, attributes = listOf(idAttr, kodAttr))
            val bEntity = Entity(internalId = "e2", qname = bQn)
            val rel =
                Relation(
                    internalId = "r1",
                    qname = relQn,
                    fromEntity = artiklQn,
                    toEntity = bQn,
                    cardinality = Cardinality(0, -1, 0, 1),
                )

            val entityMapping =
                Er2DbEntityMapping(
                    internalId = "m1",
                    qname = qn("map", "er2db_entity", "artikl"),
                    entity = artiklQn,
                    target = MappingTarget.Table(tableQn),
                )
            val idAttrMapping =
                Er2DbAttributeMapping(
                    internalId = "m2",
                    qname = qn("map", "er2db_attribute", "artikl.id_artiklu"),
                    attribute = idAttrQn,
                    target = AttributeMappingTarget.Column(idColQn),
                )
            val kodAttrMapping =
                Er2DbAttributeMapping(
                    internalId = "m3",
                    qname = qn("map", "er2db_attribute", "artikl.kód_artiklu"),
                    attribute = kodAttrQn,
                    target = AttributeMappingTarget.Column(kodColQn),
                )
            val relMapping =
                Er2DbRelationMapping(
                    internalId = "m4",
                    qname = qn("map", "er2db_relation", "artikl_b"),
                    relation = relQn,
                    foreignKey = fkQn,
                )

            val model =
                Model(
                    descriptor = ModelDescriptor(id = "t", name = "T", description = ""),
                    version = ModelVersion(value = "v", swappedAt = Instant.EPOCH),
                    schemas =
                        mapOf(
                            "er" to
                                ErSchema(
                                    entities = mapOf(artiklQn to artikl, bQn to bEntity),
                                    relations = mapOf(relQn to rel),
                                ),
                        ),
                    mappings = listOf(entityMapping, idAttrMapping, kodAttrMapping, relMapping),
                    queries = emptyMap(),
                )

            // ----- NEW: inline export (er.ttr carries the mappings) -----
            val newFiles =
                ModelToDefinitions.convert(model).files.associate { f ->
                    "/${f.filename}" to TtrRenderer.renderFile(f.schemaCode, f.namespace, f.definitions)
                }
            val newIds = ids(load(newFiles))

            // ----- OLD: standalone def er2db_* rendered into map.ttr -----
            val standaloneDefs =
                listOf(
                    ModelToDefinitions.er2dbEntityMappingToDef(entityMapping),
                    ModelToDefinitions.er2dbAttributeMappingToDef(idAttrMapping),
                    ModelToDefinitions.er2dbAttributeMappingToDef(kodAttrMapping),
                    ModelToDefinitions.er2dbRelationMappingToDef(relMapping),
                )
            val oldFiles = mapOf("/map.ttr" to TtrRenderer.renderFile("binding", null, standaloneDefs))
            val oldIds = ids(load(oldFiles))

            newIds shouldBe oldIds
            // sanity: the expected four mapping identities are present
            newIds shouldBe
                setOf(
                    "er2db_entity_mapping:artikl",
                    "er2db_attribute_mapping:artikl.id_artiklu",
                    "er2db_attribute_mapping:artikl.kód_artiklu",
                    "er2db_relation_mapping:artikl_b",
                )
        }
    })
