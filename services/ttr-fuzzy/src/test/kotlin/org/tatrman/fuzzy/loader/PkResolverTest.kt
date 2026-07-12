// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.meta.v1.DbTableDetail

class PkResolverTest :
    StringSpec({
        "singleColumnPkOrNull — empty primary_key returns null" {
            val table = DbTableDetail.newBuilder().build()
            singleColumnPkOrNull(table) shouldBe null
            pkReason(table) shouldBe "no_pk"
        }

        "singleColumnPkOrNull — one entry returns that entry" {
            val table = DbTableDetail.newBuilder().addPrimaryKey("id").build()
            singleColumnPkOrNull(table) shouldBe "id"
            pkReason(table) shouldBe null
        }

        "singleColumnPkOrNull — two entries returns null for composite PK" {
            val table =
                DbTableDetail
                    .newBuilder()
                    .addPrimaryKey("order_id")
                    .addPrimaryKey("line_num")
                    .build()
            singleColumnPkOrNull(table) shouldBe null
            pkReason(table) shouldBe "composite_pk"
        }

        "singleColumnPkOrNull — three entries also returns null" {
            val table =
                DbTableDetail
                    .newBuilder()
                    .addPrimaryKey("a")
                    .addPrimaryKey("b")
                    .addPrimaryKey("c")
                    .build()
            singleColumnPkOrNull(table) shouldBe null
            pkReason(table) shouldBe "composite_pk"
        }
    })
