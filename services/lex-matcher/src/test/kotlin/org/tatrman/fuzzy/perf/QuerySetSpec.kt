// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.perf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** T2 — the query set must be deterministic and have the designed class counts. */
class QuerySetSpec :
    StringSpec({
        val products = CorpusGenerator.products(2000, seed = 42)

        "same corpus + seed ⇒ identical query set" {
            val a = QuerySet.build(products, "products", seed = 99)
            val b = QuerySet.build(products, "products", seed = 99)
            a shouldBe b
        }

        "different seed ⇒ different queries" {
            val a = QuerySet.build(products, "products", seed = 1).map { it.query }
            val b = QuerySet.build(products, "products", seed = 2).map { it.query }
            (a == b) shouldBe false
        }

        "each class is generated the designed number of times (25 → 200 total)" {
            val queries = QuerySet.build(products, "products", seed = 7)
            queries.size shouldBe 25 * QueryClass.values().size
            QueryClass.values().forEach { cls ->
                queries.count { it.cls == cls } shouldBe 25
            }
        }

        "category assignment matches the class contract" {
            val queries = QuerySet.build(products, "products", seed = 7)
            queries.filter { it.cls == QueryClass.CROSS_CATEGORY }.forEach { it.category shouldBe null }
            queries.filter { it.cls == QueryClass.UNKNOWN_CATEGORY }.forEach {
                it.category shouldBe QuerySet.UNKNOWN_CATEGORY_KEY
            }
            queries.filter { it.cls == QueryClass.EXACT_SUBSET }.forEach { it.category shouldBe "products" }
        }

        "query shapes: SHORT is one token, EXACT_SUBSET/REORDERED two, LONG five" {
            val queries = QuerySet.build(products, "products", seed = 7)
            queries.filter { it.cls == QueryClass.SHORT }.forEach {
                it.query
                    .trim()
                    .split(" ")
                    .size shouldBe 1
            }
            queries.filter { it.cls == QueryClass.EXACT_SUBSET }.forEach {
                it.query
                    .trim()
                    .split(" ")
                    .size shouldBe 2
            }
            queries.filter { it.cls == QueryClass.REORDERED }.forEach {
                it.query
                    .trim()
                    .split(" ")
                    .size shouldBe 2
            }
            queries.filter { it.cls == QueryClass.LONG }.forEach {
                it.query
                    .trim()
                    .split(" ")
                    .size shouldBe 5
            }
        }

        "REORDERED is the token-reverse of an exact subset shape (order genuinely changes)" {
            // A reordered 2-token query, reversed, is a plausible in-order subset — i.e. reversing
            // it produces a different string (the two rare tokens are distinct).
            val queries = QuerySet.build(products, "products", seed = 7)
            queries.filter { it.cls == QueryClass.REORDERED }.forEach {
                val toks = it.query.trim().split(" ")
                toks.joinToString(" ") shouldNotBe toks.reversed().joinToString(" ")
            }
        }
    })
