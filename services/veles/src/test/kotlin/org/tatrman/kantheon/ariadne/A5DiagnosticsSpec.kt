package org.tatrman.kantheon.ariadne

import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.reconcile.ModelReconciler
import org.tatrman.kantheon.ariadne.reconcile.ReconciliationResult
import org.tatrman.kantheon.ariadne.source.FileBasedSource
import org.tatrman.kantheon.ariadne.source.LocalFsStorage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Stage 10 Task 4 — the A5 package/import diagnostics fire with the right severity.
 *
 * Error-severity codes (`wrong-file-kind`, `package-declaration-mismatch`) land on
 * `errors` (reconcile not ok); warning/info codes (`unused-import`,
 * `wildcard-with-no-matches`, `duplicate-import`, `circular-package-dependency`,
 * `missing-package-declaration`) land on `warnings` and never block the load.
 * (`unimported-reference` / `ambiguous-reference` are covered by
 * PackageResolutionSpec / ReferenceResolverSpec / StockRoleResolutionSpec.)
 */
class A5DiagnosticsSpec :
    StringSpec({

        fun write(
            root: Path,
            rel: String,
            content: String,
        ) {
            val p = root.resolve(rel)
            Files.createDirectories(p.parent)
            Files.writeString(p, content.trimIndent())
        }

        fun reconcile(root: Path): ReconciliationResult {
            val source =
                FileBasedSource(
                    sourceId = "diag",
                    priority = 100,
                    storage = LocalFsStorage(id = "diag", rootPath = root),
                )
            return ModelReconciler(ModelDescriptor(id = "d", name = "d")).reconcile(listOf(source.load()))
        }

        fun ReconciliationResult.errHas(code: String) = errors.any { it.message.contains(code) }

        fun ReconciliationResult.warnHas(code: String) = warnings.any { it.message.contains(code) }

        "ttr/wrong-file-kind (ERROR) — a `def role` in a `schema db` file blocks load" {
            val root = Files.createTempDirectory("diag-wfk")
            write(
                root,
                "bad.ttr",
                """
                schema db namespace dbo
                def role myrole { }
                """,
            )
            val r = reconcile(root)
            r.errHas("ttr/wrong-file-kind") shouldBe true
            r.ok shouldBe false
        }

        "ttr/package-declaration-mismatch (ERROR) — declared package != directory blocks load" {
            val root = Files.createTempDirectory("diag-pdm")
            write(
                root,
                "foo/x.ttr",
                """
                package bar
                schema er namespace entity
                def entity E { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            val r = reconcile(root)
            r.errHas("ttr/package-declaration-mismatch") shouldBe true
            r.ok shouldBe false
        }

        "ttr/missing-package-declaration (Info/warn) — subdir file with no package, non-blocking" {
            val root = Files.createTempDirectory("diag-mpd")
            write(
                root,
                "foo/x.ttr",
                """
                schema er namespace entity
                def entity E { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            val r = reconcile(root)
            r.warnHas("ttr/missing-package-declaration") shouldBe true
            r.errHas("ttr/missing-package-declaration") shouldBe false
        }

        "ttr/unused-import (warn) — a named import no reference uses, non-blocking" {
            val root = Files.createTempDirectory("diag-unused")
            write(
                root,
                "x.ttr",
                """
                import er.entity.Customer
                schema er namespace entity
                def entity E { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            val r = reconcile(root)
            r.warnHas("ttr/unused-import") shouldBe true
        }

        "ttr/wildcard-with-no-matches (warn) — wildcard import matching nothing, non-blocking" {
            val root = Files.createTempDirectory("diag-wnm")
            write(
                root,
                "x.ttr",
                """
                import er.nothinghere.*
                schema er namespace entity
                def entity E { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            val r = reconcile(root)
            r.warnHas("ttr/wildcard-with-no-matches") shouldBe true
        }

        "ttr/duplicate-import (warn) — the same import twice, non-blocking" {
            val root = Files.createTempDirectory("diag-dup")
            write(
                root,
                "x.ttr",
                """
                import er.entity.*
                import er.entity.*
                schema er namespace entity
                def entity E { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            val r = reconcile(root)
            r.warnHas("ttr/duplicate-import") shouldBe true
        }

        "ttr/circular-package-dependency (warn) — package A imports B imports A, non-blocking" {
            val root = Files.createTempDirectory("diag-cycle")
            write(
                root,
                "alpha/a.ttr",
                """
                package alpha
                import beta.entity.*
                schema er namespace entity
                def entity A { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            write(
                root,
                "beta/b.ttr",
                """
                package beta
                import alpha.entity.*
                schema er namespace entity
                def entity B { attributes: [ def attribute id { type: int, isKey: true } ] }
                """,
            )
            val r = reconcile(root)
            r.warnHas("ttr/circular-package-dependency") shouldBe true
        }
    })
