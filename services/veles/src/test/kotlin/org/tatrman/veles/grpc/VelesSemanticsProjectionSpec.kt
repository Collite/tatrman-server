// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.tatrman.meta.v1.GetObjectRequest
import org.tatrman.meta.v1.GetObjectResponse
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.veles.grpc.MetadataServiceImpl
import java.nio.file.Path

/**
 * RG-P3.S0.T2 — the semantics projection spec (test-first). Pins that Veles projects
 * the grammar-4.2 `semantics { }` surface (grounding declarations) onto `meta.v1`:
 *
 *   fixture-semantics/{59-semantics.ttrm (er), 60-semantics-db.ttrm (db)}
 *     -> FileBasedSource -> ModelReconciler -> MetadataRegistry -> MetadataServiceImpl
 *     -> get_object / list_objects carry EntitySemantics / AttributeSemantics
 *
 * Provenance: 59/60-semantics are the grammar's golden conformance fixtures
 * (tatrman `tests/conformance/fixtures/`), vendored here verbatim. kind/role are
 * STRINGS (RS-33) — the open vocabulary lives in ttr-semantics, not in the proto.
 *
 * RED until RG-P3.S0.T3 adds the proto fields and T4 populates them.
 */
class VelesSemanticsProjectionSpec :
    StringSpec({

        fun serviceFrom(resourceDir: String): MetadataServiceImpl {
            val root = Path.of(checkNotNull(this::class.java.classLoader.getResource(resourceDir)).toURI())
            val source =
                FileBasedSource(
                    sourceId = resourceDir,
                    priority = 100,
                    storage = LocalFsStorage(id = resourceDir, rootPath = root),
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "test", name = "test", description = resourceDir))
            val result = reconciler.reconcile(listOf(source.load()))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry)
        }

        suspend fun MetadataServiceImpl.descriptorByName(
            kind: String,
            localName: String,
        ): QualifiedName {
            val items = listObjects(ListObjectsRequest.newBuilder().setKind(kind).build()).itemsList
            return items.first { it.localName == localName }.qualifiedName
        }

        suspend fun MetadataServiceImpl.getByName(
            kind: String,
            localName: String,
        ): GetObjectResponse =
            getObject(
                GetObjectRequest.newBuilder().setQualifiedName(descriptorByName(kind, localName)).build(),
            )

        // ---- (a) er entity kind + period role columns + code_format ----

        "get_object on a period_table entity carries EntitySemantics.kind" {
            val svc = serviceFrom("fixture-semantics")
            val r = svc.getByName("entity", "AccountingPeriod")
            r.entity.hasSemantics() shouldBe true
            r.entity.semantics.kind shouldBe "period_table"
        }

        "period_table role attributes carry role + code_format" {
            val svc = serviceFrom("fixture-semantics")
            svc
                .getByName("attribute", "start_date")
                .attribute.semantics.role shouldBe "period_start"
            svc
                .getByName("attribute", "end_date")
                .attribute.semantics.role shouldBe "period_end"
            val period = svc.getByName("attribute", "period").attribute.semantics
            period.role shouldBe "period_code"
            period.codeFormat shouldBe "yyyyMM"
        }

        // ---- (b) event_date -> resolved period qname; amount -> currency_attribute ----

        "event_date attribute resolves its period reference to the period entity" {
            val svc = serviceFrom("fixture-semantics")
            val sem = svc.getByName("attribute", "txn_date").attribute.semantics
            sem.role shouldBe "event_date"
            sem.hasPeriod() shouldBe true
            sem.period.name shouldBe "AccountingPeriod"
        }

        "amount attribute carries its currency sibling-attribute local name" {
            val svc = serviceFrom("fixture-semantics")
            val sem = svc.getByName("attribute", "amount").attribute.semantics
            sem.role shouldBe "amount"
            sem.currencyAttribute shouldBe "currency_code"
        }

        // ---- (c) db twin: table kind + column roles from ONE get_object(table) ----

        "get_object on a db period table carries table + column semantics in one call" {
            val svc = serviceFrom("fixture-semantics")
            val table = svc.getByName("table", "accounting_period").table
            table.semantics.kind shouldBe "period_table"
            val periodCol = table.columnsList.first { it.name == "period" }
            periodCol.semantics.role shouldBe "period_code"
            periodCol.semantics.codeFormat shouldBe "yyyyMM"
            table.columnsList
                .first { it.name == "start_date" }
                .semantics.role shouldBe "period_start"
        }

        // ---- (d) list_objects descriptors carry semantics_kind (discovery accelerator) ----

        "list_objects descriptors carry semantics_kind for kinded objects, empty otherwise" {
            val svc = serviceFrom("fixture-semantics")
            val entities = svc.listObjects(ListObjectsRequest.newBuilder().setKind("entity").build()).itemsList
            entities.first { it.localName == "AccountingPeriod" }.semanticsKind shouldBe "period_table"
            entities.first { it.localName == "PoiLatLon" }.semanticsKind shouldBe "poi"
            entities.first { it.localName == "FxRate" }.semanticsKind shouldBe "fx_rate"
            // Transaction declares no entity-level kind → empty.
            entities.first { it.localName == "Transaction" }.semanticsKind shouldBe ""
        }

        // ---- (e) an object with NO semantics has the field unset ----

        "an entity without a semantics block has semantics unset (not empty-kind)" {
            val svc = serviceFrom("fixture-semantics")
            val r = svc.getByName("entity", "Transaction")
            r.entity.hasSemantics() shouldBe false
        }

        // ---- (f) invalid semantics degrades: object served w/o semantics + load warning ----

        "an invalid semantics block degrades — object served without semantics + a load issue" {
            val svc = serviceFrom("fixture-semantics-invalid")
            // The object still loads and is served (no gRPC error, structural detail intact)…
            val r = svc.getByName("attribute", "booked")
            r.messagesList.none { it.code == "object_not_found" } shouldBe true
            r.attribute.type.shouldNotBeEmpty()
            // …but the unresolved period reference is NOT projected as semantics,
            r.attribute.hasSemantics() shouldBe false
            // …and the load issue surfaces through the model status/validation path.
            val validate =
                svc.validateModel(
                    org.tatrman.meta.v1.ValidateModelRequest
                        .getDefaultInstance(),
                )
            (validate.warningsCount + validate.errorsCount) shouldBeGreaterThan 0
        }
    })
