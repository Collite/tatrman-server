// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.perf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/** T1 — the corpus generator must be perfectly deterministic (a flaky golden poisons the effort). */
class CorpusGeneratorSpec :
    StringSpec({
        "products: same seed ⇒ byte-identical list" {
            val a = CorpusGenerator.products(500, seed = 7)
            val b = CorpusGenerator.products(500, seed = 7)
            a.map { it.id to it.value } shouldBe b.map { it.id to it.value }
        }

        "customers: same seed ⇒ byte-identical list" {
            val a = CorpusGenerator.customers(300, seed = 7)
            val b = CorpusGenerator.customers(300, seed = 7)
            a.map { it.id to it.value } shouldBe b.map { it.id to it.value }
        }

        "different seeds ⇒ different corpora (the seed actually drives generation)" {
            val a = CorpusGenerator.products(500, seed = 1).map { it.value }
            val b = CorpusGenerator.products(500, seed = 2).map { it.value }
            (a == b) shouldBe false
        }

        "n is respected and ids are 1-based zero-padded" {
            val products = CorpusGenerator.products(123, seed = 3)
            products shouldHaveSize 123
            products.first().id shouldBe "P000001"
            products.last().id shouldBe "P000123"

            val customers = CorpusGenerator.customers(45, seed = 3)
            customers shouldHaveSize 45
            customers.first().id shouldBe "C000001"
            customers.first().id shouldStartWith "C"
        }

        "customers carry diacritics so NFD folding is exercised" {
            val customers = CorpusGenerator.customers(200, seed = 9)
            // At least one raw value has a non-ASCII (accented) character …
            customers.any { c -> c.value.any { it.code > 127 } } shouldBe true
            // … and folding strips it (tokens are ASCII-folded).
            customers.all { c -> c.tokens.all { tok -> tok.all { it.code <= 127 } } } shouldBe true
        }

        "products carry 3–5 folded tokens (blank form/pack dropped; 'bez kofeinu' can push higher)" {
            val products = CorpusGenerator.products(1000, seed = 11)
            products.all { it.tokens.size in 3..6 } shouldBe true
            // Some products reach ≥5 tokens (needed for the LONG query class).
            products.any { it.tokens.size >= 5 } shouldBe true
        }
    })
