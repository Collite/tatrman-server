// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.fuzzy.core.SourceTag

/**
 * The static catalog loader (`fuzzy.loader.source = static`) reads the in-repo
 * `fuzzy-catalog.json`. This locks two things: (1) the shipped seed loads and is
 * non-empty (so `/ready` flips true instead of the 0-category empty boot), and
 * (2) an entry with `targetRef` becomes a VOCABULARY candidate while a plain
 * `{id,value}` stays a MEMBER — the distinction the resolver's provenance relies on.
 */
class FuzzyCatalogTest :
    StringSpec({

        "the shipped fuzzy-catalog.json loads with MEMBER products and a VOCABULARY branch term" {
            val catalog = FuzzyCatalog.fromResource("/fuzzy-catalog.json")

            catalog.keys shouldContainAll setOf("er.product", "er.branch")

            val octavia = catalog.getValue("er.product").single { it.id == "p-octavia" }
            octavia.source shouldBe SourceTag.MEMBER
            octavia.targetRef.shouldBeNull()

            val pobocka = catalog.getValue("er.branch").single()
            pobocka.id shouldBe "term-pobocka"
            pobocka.source shouldBe SourceTag.VOCABULARY
            pobocka.targetRef shouldBe "er.branch#term-pobocka"
        }
    })
