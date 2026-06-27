package org.tatrman.kantheon.ariadne.source

import org.tatrman.kantheon.ariadne.model.Er2CncRoleMapping
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.reconcile.ModelReconciler
import org.tatrman.kantheon.ariadne.reconcile.ReconciliationResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * End-to-end coverage for the stock-role auto-import (`cnc.*`) path through the
 * real `BuiltinStockSource` → `FileBasedSource` → `ModelReconciler` pipeline.
 *
 * Proves two things review-022 found unverified:
 *  - a bare `roles: [fact]` reference in a user `.ttr` resolves (no false
 *    `ttr/unimported-reference`) because `BuiltinStockSource` now exposes its
 *    stock-role definitions to the `ReferenceResolutionPass` symbol table, and
 *  - the desugared mapping's `role` qname is the canonical 4-part
 *    `cnc.cnc.role.fact` (package `cnc`), matching the registered stock role.
 */
class StockRoleResolutionSpec :
    StringSpec({

        fun reconcileWithStockRoles(userTtr: String): ReconciliationResult {
            val tmp = Files.createTempDirectory("stockrole")
            Files.writeString(tmp.resolve("orders.ttr"), userTtr)
            val sources =
                listOf(
                    BuiltinStockSource(),
                    FileBasedSource(
                        sourceId = "user",
                        priority = 100,
                        storage = LocalFsStorage(id = "user", rootPath = tmp),
                    ),
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "t", name = "t"))
            return reconciler.reconcile(sources.map { it.load() })
        }

        fun ReconciliationResult.unimportedErrors() = errors.filter { it.message.contains("unimported-reference") }

        "STOCK_ROLE_NAMES stays in sync with cnc-stock-roles.ttr (drift guard)" {
            // The constant is consumed by the TTR loader (FileBasedSource / GitArchiveStorage)
            // to decide which entity_type values map to a stock role; if the .ttr vocabulary
            // changes and the constant
            // doesn't (or vice versa), this fails loudly instead of silently dropping mappings.
            val rolesFromTtr =
                BuiltinStockSource()
                    .load()
                    .roles.keys
                    .map { it.name }
                    .toSet()
            rolesFromTtr shouldBe BuiltinStockSource.STOCK_ROLE_NAMES
        }

        "bare stock-role reference resolves via auto-import and desugars to the 4-part qname (positive)" {
            val result =
                reconcileWithStockRoles(
                    """
                    schema er namespace entity

                    def entity objednavka {
                        description: "order"
                        nameAttribute: id
                        roles: [fact]
                        attributes: [
                            def attribute id { type: int, isKey: true }
                        ]
                    }
                    """.trimIndent(),
                )

            // The auto-import (`cnc.*`) step found `fact` — no false unimported error.
            result.unimportedErrors() shouldHaveSize 0

            // …and the desugared mapping points at the canonical 4-part stock role.
            val factMapping =
                result.model.mappings
                    .filterIsInstance<Er2CncRoleMapping>()
                    .single { it.role.name == "fact" }
            factMapping.role.`package` shouldBe "cnc"
            factMapping.role.namespace shouldBe "role"
            factMapping.role.name shouldBe "fact"
        }

        "a bare reference to a non-stock role still produces unimported-reference (negative)" {
            val result =
                reconcileWithStockRoles(
                    """
                    schema er namespace entity

                    def entity objednavka {
                        description: "order"
                        nameAttribute: id
                        roles: [notarealrole]
                        attributes: [
                            def attribute id { type: int, isKey: true }
                        ]
                    }
                    """.trimIndent(),
                )

            val unimported = result.unimportedErrors()
            unimported shouldHaveSize 1
            (unimported.single().message.contains("notarealrole")) shouldBe true
        }
    })
