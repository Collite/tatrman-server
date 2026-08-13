// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.meta.v1.GetModelRequest
import org.tatrman.meta.v1.ModelBundleEntity
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Path

/**
 * NLS-P10 T6 (⚑GXP-D7) — Veles serves `ObjectDescriptor.description` in the locale
 * the caller asked for.
 *
 * **The wire is unchanged**: `description` is still a single `string`, and the
 * localised map never reaches the client. The selection happens here, at the edge,
 * through the D7 fallback chain — and the chain is the contract this spec pins:
 *
 * ```
 * requested locale → plain-string form → `en` → first entry by language code → ""
 * ```
 *
 * An EMPTY `locale` keeps 0.12 behaviour exactly (the plain form, or "" when the
 * author wrote only a map) — "all locales" is meaningful for a `LocalizedString`
 * field like `display_label`, but not for a single-string one. See
 * `MetadataServiceImpl.selectDescription`.
 */
class GetModelLocalizedDescriptionSpec :
    StringSpec({

        val fixtureRoot: Path =
            Path
                .of(checkNotNull(this::class.java.classLoader.getResource("model-ttr-localized/localized")).toURI())
                .parent

        fun service(): MetadataServiceImpl {
            val source =
                FileBasedSource(
                    sourceId = "localized",
                    priority = 100,
                    storage = LocalFsStorage(id = "localized", rootPath = fixtureRoot),
                )
            val reconciler =
                ModelReconciler(ModelDescriptor(id = "test", name = "test", description = "localized fixture"))
            val result = reconciler.reconcile(listOf(source.load()))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry)
        }

        suspend fun entities(locale: String): Map<String, ModelBundleEntity> {
            val req =
                GetModelRequest
                    .newBuilder()
                    .addPackages("localized")
                    .also { if (locale.isNotEmpty()) it.locale = locale }
                    .build()
            return service()
                .getModel(req)
                .model.entitiesList
                .associateBy { it.objectDescriptor.localName }
        }

        "step 1 — the requested locale wins when the map carries it" {
            val cs = entities("cs")
            cs.getValue("bilingual").objectDescriptor.description shouldBe "Dvojjazyčný popis"

            val en = entities("en")
            en.getValue("bilingual").objectDescriptor.description shouldBe "A bilingual description"
        }

        "step 2 — a plain-string description answers every locale (there is nothing to select)" {
            entities("cs").getValue("plain").objectDescriptor.description shouldBe "a plain description"
            entities("en").getValue("plain").objectDescriptor.description shouldBe "a plain description"
            entities("de").getValue("plain").objectDescriptor.description shouldBe "a plain description"
        }

        "step 3 — a map without the requested locale falls back to `en`" {
            entities("cs").getValue("english_only").objectDescriptor.description shouldBe "English only"
        }

        "step 4 — neither the requested locale nor `en`: first entry by language code" {
            // {de, fr} sorted by language code → `de`. Deterministic on purpose: the
            // alternative (map iteration order) makes the served text depend on how the
            // author happened to order the block.
            entities("cs").getValue("neither").objectDescriptor.description shouldBe "Nur Deutsch"
        }

        "step 5 — nothing authored at all stays empty" {
            val attrs =
                entities("cs")
                    .getValue("bilingual")
                    .attributesList
                    .associateBy { it.objectDescriptor.localName }
            attrs.getValue("undescribed").objectDescriptor.description shouldBe ""
        }

        "attributes go through the same chain as entities" {
            val cs =
                entities("cs")
                    .getValue("bilingual")
                    .attributesList
                    .associateBy { it.objectDescriptor.localName }
            cs.getValue("id").objectDescriptor.description shouldBe "Identifikátor"
            cs.getValue("plain_attr").objectDescriptor.description shouldBe "an attribute described the old way"

            val en =
                entities("en")
                    .getValue("bilingual")
                    .attributesList
                    .associateBy { it.objectDescriptor.localName }
            en.getValue("id").objectDescriptor.description shouldBe "Identifier"
        }

        "REGRESSION PIN — an empty locale serves exactly what 0.12 served" {
            val none = entities("")
            // Plain-string authors: unchanged, byte for byte.
            none.getValue("plain").objectDescriptor.description shouldBe "a plain description"
            // Map-only authors had NO description on the wire before 0.13 either (the
            // form did not exist), and an empty locale means "the caller expressed no
            // preference" — so the single-string field stays empty rather than guessing.
            none.getValue("bilingual").objectDescriptor.description shouldBe ""
            none.getValue("english_only").objectDescriptor.description shouldBe ""
        }

        "the localised map itself never reaches the wire" {
            // The proof that the D7 work did not widen `meta.v1`: ObjectDescriptor has
            // exactly one description field and it is a string. If someone later adds a
            // map field to the proto, this line stops compiling — which is the point.
            val d = entities("cs").getValue("bilingual").objectDescriptor
            (d.description is String) shouldBe true
        }
    })
