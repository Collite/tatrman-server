package org.tatrman.kantheon.ariadne.source

import org.tatrman.kantheon.ariadne.model.Er2DbAttributeMapping
import org.tatrman.kantheon.ariadne.model.Er2DbEntityMapping
import org.tatrman.kantheon.ariadne.model.Er2DbRelationMapping
import org.tatrman.kantheon.ariadne.model.MappingSource
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.reconcile.ModelReconciler
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/**
 * v2.1 — cross-loader round-trip against modeler's canonical `samples/2.1/`
 * corpus. The same three files (`db.ttr`, `er.ttr`, `map.ttr`) are checked in
 * here under `resources/v2-1-samples/` and loaded through ai-platform's
 * `FileBasedSource` → `ModelReconciler` pipeline.
 *
 * This locks the inline-mapping contract between the two repos: any drift in
 * the grammar / walker / synthesizer / validator that changes the synthesised
 * `Er2Db*Mapping` inventory will fail this spec before it lands.
 *
 * Source of truth: `modeler/samples/2.1/{db,er,map}.ttr`. If the modeler
 * sample is intentionally changed, re-copy the files via:
 *
 *     cp ~/Dev/modeler/samples/2.1/{db,er,map}.ttr \
 *        infra/metadata/src/test/resources/v2-1-samples/
 *
 * and update the expected inventory below.
 */
class V21SamplesSpec :
    StringSpec({
        val samplesRoot: Path =
            Path.of(checkNotNull(this::class.java.classLoader.getResource("v2-1-samples")).toURI())

        fun load(): org.tatrman.kantheon.ariadne.reconcile.ReconciliationResult {
            val source =
                FileBasedSource(
                    sourceId = "v2-1-samples",
                    priority = 100,
                    storage = LocalFsStorage(id = "v2-1-samples", rootPath = samplesRoot),
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "v2-1-samples", name = "v2-1-samples"))
            return reconciler.reconcile(listOf(source.load()))
        }

        "samples/2.1 — all three files parse without errors" {
            val result = load()
            val parseErrors = result.errors.filter { it.message.contains("ttr/parse-error") }
            parseErrors shouldHaveSize 0
        }

        "samples/2.1 — canonical (non-broken) corpus emits zero ttr/duplicate-mapping" {
            val result = load()
            val dup = result.errors.filter { it.message.contains("ttr/duplicate-mapping") }
            dup shouldHaveSize 0
        }

        "samples/2.1 — entity-level inline mapping (artikl) synthesises Er2DbEntityMapping + 5 columns" {
            val result = load()
            val entityMappings = result.model.mappings.filterIsInstance<Er2DbEntityMapping>()

            val artikl = entityMappings.single { it.qname.name == "artikl" }
            artikl.mappingSource shouldBe MappingSource.Inline("entity")

            // 5 inline-entity attribute mappings for artikl's columns map
            val artiklAttrs =
                result.model.mappings
                    .filterIsInstance<Er2DbAttributeMapping>()
                    .filter { it.qname.name.startsWith("artikl.") }
            artiklAttrs shouldHaveSize 5
            artiklAttrs.all { it.mappingSource == MappingSource.Inline("entity") } shouldBe true
            artiklAttrs.map { it.qname.name }.toSet() shouldBe
                setOf(
                    "artikl.id_artiklu",
                    "artikl.kód_artiklu",
                    "artikl.název_artiklu",
                    "artikl.id_produktu",
                    "artikl.id_podproduktu",
                )
        }

        "samples/2.1 — attribute-level inline mapping (produkt) synthesises 3 Er2DbAttributeMapping records" {
            val result = load()
            val produktAttrs =
                result.model.mappings
                    .filterIsInstance<Er2DbAttributeMapping>()
                    .filter { it.qname.name.startsWith("produkt.") }
            produktAttrs shouldHaveSize 3
            produktAttrs.all { it.mappingSource == MappingSource.Inline("attribute") } shouldBe true
            produktAttrs.map { it.qname.name }.toSet() shouldBe
                setOf("produkt.id_produktu", "produkt.kód_produktu", "produkt.název_produktu")
        }

        "samples/2.1 — relation-level inline mappings (artikl_produkt + artikl_podprodukt) both Inline" {
            val result = load()
            val relMappings = result.model.mappings.filterIsInstance<Er2DbRelationMapping>()
            val inline = relMappings.filter { it.mappingSource is MappingSource.Inline }
            inline.map { it.qname.name }.toSet() shouldBe
                setOf("artikl_produkt", "artikl_podprodukt")
        }

        "samples/2.1 — explicit map.ttr contributes 4 explicit entity mappings + 3 explicit relation mappings" {
            val result = load()
            val explicitEntities =
                result.model.mappings
                    .filterIsInstance<Er2DbEntityMapping>()
                    .filter { it.mappingSource is MappingSource.Explicit }
            explicitEntities.map { it.qname.name }.toSet() shouldBe
                setOf("podprodukt", "obchodní_kanál", "tržní_skupina", "subjekt")

            val explicitRelations =
                result.model.mappings
                    .filterIsInstance<Er2DbRelationMapping>()
                    .filter { it.mappingSource is MappingSource.Explicit }
            explicitRelations.map { it.qname.name }.toSet() shouldBe
                setOf("podprodukt_produkt", "obchodní_kanál_tržní_skupina", "subjekt_obchodní_kanál")
        }

        "samples/2.1 — full mapping inventory: 5 entity + 15 attribute + 5 relation" {
            val result = load()
            result.model.mappings.filterIsInstance<Er2DbEntityMapping>() shouldHaveSize 5
            result.model.mappings.filterIsInstance<Er2DbAttributeMapping>() shouldHaveSize 15
            result.model.mappings.filterIsInstance<Er2DbRelationMapping>() shouldHaveSize 5
        }
    })
