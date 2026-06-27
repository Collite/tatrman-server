package org.tatrman.kantheon.ariadne.source

import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.reconcile.ModelReconciler
import org.tatrman.kantheon.ariadne.reconcile.ReconciliationResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/**
 * Integration tests for package-aware reference resolution through the real
 * `FileBasedSource` → `ModelReconciler` pipeline.
 *
 * Fixture layout under `fixture-packages/` (each test asserts the referencing
 * entity actually loaded — proving its file parsed — AND that no
 * `unimported-reference` was produced — proving its reference resolved):
 *   sales/Product.ttr       package sales      entity Product (resolution target)
 *   sales/sales-order.ttr   package sales      SalesOrder  roles:[Product]  (same-package, no import — step 2)
 *   catalog/Catalog.ttr     package catalog    Catalog     import er.sales.Product, roles:[Product] (named — step 3)
 *   relations/Order.ttr     package relations  Order       import er.sales.*,       roles:[Product] (wildcard — step 4)
 *
 * `fixture-packages-noimport/` holds the negative case (a cross-package ref with no import).
 *
 * Note: wildcard non-recursion and fully-qualified resolution are exercised at the
 * unit level in `org.tatrman.kantheon.ariadne.resolve.ReferenceResolverSpec`; they cannot be
 * expressed here because every model symbol is a fixed 3-segment
 * `schemaCode.namespace.name` qname (there is no deeper level for a wildcard to
 * over-reach, and a dotted fully-qualified ref is not writable in a `roles: [...]` list).
 */
class PackageResolutionSpec :
    StringSpec({
        val fixtureRoot: Path =
            Path.of(checkNotNull(this::class.java.classLoader.getResource("fixture-model")).toURI())

        val packagesRoot: Path =
            Path.of(checkNotNull(this::class.java.classLoader.getResource("fixture-packages")).toURI())

        val noImportRoot: Path =
            Path.of(checkNotNull(this::class.java.classLoader.getResource("fixture-packages-noimport")).toURI())

        fun loadPackages(root: Path): ReconciliationResult {
            val source =
                FileBasedSource(
                    sourceId = "pkg",
                    priority = 100,
                    storage = LocalFsStorage(id = "pkg", rootPath = root),
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "pkg-test", name = "pkg-test"))
            return reconciler.reconcile(listOf(source.load()))
        }

        fun ReconciliationResult.entityNamed(name: String) =
            model.objectByQname().values.filter { it.kind == "entity" && it.qname.name == name }

        fun ReconciliationResult.unimportedErrors() = errors.filter { it.message.contains("ttr/unimported-reference") }

        "same-package reference resolves with no import (step 2)" {
            val result = loadPackages(packagesRoot)
            // SalesOrder must have loaded (guards against a silently-skipped/unparsed fixture)…
            result.entityNamed("SalesOrder") shouldHaveSize 1
            // …and its same-package roles:[Product] reference must have resolved.
            result.unimportedErrors() shouldHaveSize 0
        }

        "named import resolves a cross-package reference (step 3)" {
            val result = loadPackages(packagesRoot)
            result.entityNamed("Catalog") shouldHaveSize 1
            result.unimportedErrors() shouldHaveSize 0
        }

        "wildcard import resolves a cross-package reference (step 4)" {
            val result = loadPackages(packagesRoot)
            result.entityNamed("Order") shouldHaveSize 1
            // The resolution target itself loaded under the expected namespace…
            val product = result.entityNamed("Product")
            product shouldHaveSize 1
            product.first().qname.namespace shouldBe "sales"
            // …and Order's wildcard-imported roles:[Product] reference resolved.
            result.unimportedErrors() shouldHaveSize 0
        }

        "regression: existing single-file model with no package/imports resolves identically" {
            val fixtureSource =
                FileBasedSource(
                    sourceId = "fixture",
                    priority = 100,
                    storage = LocalFsStorage(id = "fixture", rootPath = fixtureRoot),
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "test", name = "test"))
            val result = reconciler.reconcile(listOf(fixtureSource.load()))
            result.errors.size shouldBe 0
            val tables =
                result.model.schemas["db"]
                    ?.objects()
                    ?.filter { it.kind == "table" }
                    ?.toList()
                    ?: emptyList()
            tables.size shouldBe 2
        }

        ".ttrg file is never read (no defs, no error)" {
            val source =
                FileBasedSource(
                    sourceId = "ttrg-test",
                    priority = 100,
                    storage = LocalFsStorage(id = "ttrg-test", rootPath = fixtureRoot),
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "ttrg-test", name = "ttrg-test"))
            val result = reconciler.reconcile(listOf(source.load()))
            val ttrgObjects =
                result.model
                    .objectByQname()
                    .values
                    .filter { it.sourceFile.contains(".ttrg") }
            ttrgObjects.toList() shouldHaveSize 0
        }

        "cross-package ref with no matching import produces unimported-reference (negative test)" {
            val result = loadPackages(noImportRoot)
            // The referencing entity loaded (so the file parsed)…
            result.entityNamed("OrderWithBadRole") shouldHaveSize 1
            // …but its unimported cross-package roles:[Product] reference did NOT resolve.
            result.unimportedErrors() shouldHaveSize 1
        }
    })
