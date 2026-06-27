package org.tatrman.kantheon.ariadne.resolve

import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.reconcile.ModelReconciler
import org.tatrman.kantheon.ariadne.source.LoadedFile
import org.tatrman.kantheon.ariadne.source.SourceSnapshot
import org.tatrman.kantheon.ariadne.source.StorageFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.Reference
import org.tatrman.ttr.parser.model.SourceLocation
import java.nio.file.Path

class ResolutionIntegrationSpec :
    StringSpec({
        "same-package entity ref resolves without import" {
            val customerFile =
                LoadedFile(
                    storageFile =
                        StorageFile(
                            path = "/test/sales/Customer.ttr",
                            sizeBytes = 100,
                            rootPath = Path.of("/test"),
                        ),
                    computedPackage = "sales",
                    declaredPackage = null,
                    imports = emptyList(),
                    definitions =
                        listOf(
                            EntityDef(
                                name = "Customer",
                                source = SourceLocation("/test/sales/Customer.ttr", 1, 1, 1, 1, 0, 0),
                                description = null,
                                tags = emptyList(),
                                labelPlural = null,
                                nameAttribute = Reference("name"),
                                codeAttribute = null,
                                aliases = emptyList(),
                                attributes =
                                    listOf(
                                        org.tatrman.ttr.parser.model.AttributeDef(
                                            name = "id",
                                            source = SourceLocation("/test/sales/Customer.ttr", 1, 1, 1, 1, 0, 0),
                                            description = null,
                                            tags = emptyList(),
                                        ),
                                    ),
                                roles = emptyList(),
                            ),
                        ),
                    schemaCode = "er",
                    namespace = "entity",
                )
            val orderFile =
                LoadedFile(
                    storageFile =
                        StorageFile(
                            path = "/test/sales/Order.ttr",
                            sizeBytes = 100,
                            rootPath = Path.of("/test"),
                        ),
                    computedPackage = "sales",
                    declaredPackage = null,
                    imports = emptyList(),
                    definitions =
                        listOf(
                            EntityDef(
                                name = "Order",
                                source = SourceLocation("/test/sales/Order.ttr", 1, 1, 1, 1, 0, 0),
                                description = null,
                                tags = emptyList(),
                                labelPlural = null,
                                nameAttribute = Reference("id"),
                                codeAttribute = null,
                                aliases = emptyList(),
                                attributes =
                                    listOf(
                                        org.tatrman.ttr.parser.model.AttributeDef(
                                            name = "customer_ref",
                                            source = SourceLocation("/test/sales/Order.ttr", 1, 1, 1, 1, 0, 0),
                                            description = null,
                                            tags = emptyList(),
                                        ),
                                    ),
                                roles = listOf(Reference("Customer")),
                            ),
                        ),
                    schemaCode = "er",
                    namespace = "entity",
                )
            val snapshot =
                SourceSnapshot(
                    sourceId = "test",
                    priority = 100,
                    version = "1",
                    tables = emptyMap(),
                    views = emptyMap(),
                    procedures = emptyMap(),
                    foreignKeys = emptyMap(),
                    entities = emptyMap(),
                    relations = emptyMap(),
                    mappings = emptyList(),
                    queries = emptyMap(),
                    roles = emptyMap(),
                    loadedFiles = listOf(customerFile, orderFile),
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "test", name = "test"))
            val result = reconciler.reconcile(listOf(snapshot))
            val unimportedErrors = result.errors.filter { it.message.contains("ttr/unimported-reference") }
            unimportedErrors.shouldHaveSize(0)
        }

        "same-package ref resolves with non-default namespace (sales) — F1 regression" {
            val customerFile =
                LoadedFile(
                    storageFile =
                        StorageFile(
                            path = "/test/sales/Customer.ttr",
                            sizeBytes = 100,
                            rootPath = Path.of("/test"),
                        ),
                    computedPackage = "sales",
                    declaredPackage = null,
                    imports = emptyList(),
                    definitions =
                        listOf(
                            EntityDef(
                                name = "Customer",
                                source = SourceLocation("/test/sales/Customer.ttr", 1, 1, 1, 1, 0, 0),
                                description = null,
                                tags = emptyList(),
                                labelPlural = null,
                                nameAttribute = null,
                                codeAttribute = null,
                                aliases = emptyList(),
                                attributes =
                                    listOf(
                                        org.tatrman.ttr.parser.model.AttributeDef(
                                            name = "id",
                                            source = SourceLocation("/test/sales/Customer.ttr", 1, 1, 1, 1, 0, 0),
                                            description = null,
                                            tags = emptyList(),
                                        ),
                                    ),
                                roles = emptyList(),
                            ),
                        ),
                    schemaCode = "er",
                    namespace = "sales",
                )
            val orderFile =
                LoadedFile(
                    storageFile =
                        StorageFile(
                            path = "/test/sales/Order.ttr",
                            sizeBytes = 100,
                            rootPath = Path.of("/test"),
                        ),
                    computedPackage = "sales",
                    declaredPackage = null,
                    imports = emptyList(),
                    definitions =
                        listOf(
                            EntityDef(
                                name = "Order",
                                source = SourceLocation("/test/sales/Order.ttr", 1, 1, 1, 1, 0, 0),
                                description = null,
                                tags = emptyList(),
                                labelPlural = null,
                                nameAttribute = null,
                                codeAttribute = null,
                                aliases = emptyList(),
                                attributes =
                                    listOf(
                                        org.tatrman.ttr.parser.model.AttributeDef(
                                            name = "customer_ref",
                                            source = SourceLocation("/test/sales/Order.ttr", 1, 1, 1, 1, 0, 0),
                                            description = null,
                                            tags = emptyList(),
                                        ),
                                    ),
                                roles = listOf(Reference("Customer")),
                            ),
                        ),
                    schemaCode = "er",
                    namespace = "sales",
                )
            val snapshot =
                SourceSnapshot(
                    sourceId = "test",
                    priority = 100,
                    version = "1",
                    tables = emptyMap(),
                    views = emptyMap(),
                    procedures = emptyMap(),
                    foreignKeys = emptyMap(),
                    entities = emptyMap(),
                    relations = emptyMap(),
                    mappings = emptyList(),
                    queries = emptyMap(),
                    roles = emptyMap(),
                    loadedFiles = listOf(customerFile, orderFile),
                )
            val pass = ReferenceResolutionPass(sourceId = "test", files = listOf(customerFile, orderFile))
            val result = pass.run()
            val customerRef =
                result.resolvedReferences.find {
                    it.ref.path == "Customer" && it.filePath.contains("Order")
                }
            customerRef.shouldBeInstanceOf<ResolvedReference>()
            customerRef.resolution.shouldBeInstanceOf<Resolution.Resolved>()
            val resolved = customerRef.resolution as Resolution.Resolved
            resolved.qualifiedName.namespace shouldBe "sales"
            resolved.qualifiedName.name shouldBe "Customer"
        }
    })
