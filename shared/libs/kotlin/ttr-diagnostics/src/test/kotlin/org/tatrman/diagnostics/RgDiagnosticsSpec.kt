// SPDX-License-Identifier: Apache-2.0
package org.tatrman.diagnostics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * RG-P0.S3.T6 — the fixture IS contracts §8. Every `RG-*` id must be registered
 * with the exact severity from the table, a non-blank message template, and a
 * non-blank suggestion. The table below is the source of truth; the registry is
 * checked against it (both directions — no missing ids, no extras).
 */
class RgDiagnosticsSpec :
    FunSpec({

        // contracts §8 — (id -> severity)
        val expected =
            mapOf(
                "RG-NLP-001" to Severity.ERROR,
                "RG-NLP-002" to Severity.WARNING,
                "RG-NLP-003" to Severity.ERROR,
                "RG-NLP-010" to Severity.INFO,
                "RG-FUZ-001" to Severity.WARNING,
                "RG-FUZ-002" to Severity.ERROR,
                "RG-GND-001" to Severity.WARNING,
                "RG-GND-002" to Severity.ERROR,
                "RG-RES-001" to Severity.INFO,
                "RG-RES-002" to Severity.ERROR,
            )

        test("registry has exactly the contracts §8 id set") {
            RgDiagnostics
                .all()
                .map { it.id }
                .shouldContainExactlyInAnyOrder(expected.keys.toList())
        }

        context("each id: registered, correct severity, non-blank template + suggestion") {
            expected.forEach { (id, severity) ->
                test("$id is $severity with a template + suggestion") {
                    val d = RgDiagnostics[id]
                    d.id shouldBe id
                    d.severity shouldBe severity
                    d.messageTemplate.shouldNotBeBlank()
                    d.suggestion.shouldNotBeBlank()
                }
            }
        }

        test("get() on an unknown id fails loudly") {
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                RgDiagnostics["RG-NOPE-999"]
            }
        }

        test("render substitutes placeholders; an RgDiagnosticException carries the diagnostic") {
            val rendered = RgDiagnostics.render("RG-NLP-010", "cs" to "cs", "op" to "DEP_PARSE")
            rendered.shouldNotBeBlank()
            val ex = RgDiagnosticException(RgDiagnostics["RG-FUZ-002"], "category=widgets")
            ex.diagnostic.id shouldBe "RG-FUZ-002"
            ex.diagnostic.severity shouldBe Severity.ERROR
        }
    })
