// SPDX-License-Identifier: Apache-2.0
package shared.formatter.input

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import shared.formatter.types.LogicalType

class JsonRowsReaderSpec :
    StringSpec({

        "empty array → zero rows / zero columns" {
            val r = JsonRowsReader.read("[]".toByteArray(Charsets.UTF_8))
            r.use {
                it.columns shouldBe emptyList()
                it.iterator().hasNext() shouldBe false
            }
        }

        "empty bytes → zero rows / zero columns" {
            JsonRowsReader.read(byteArrayOf()).use {
                it.columns shouldBe emptyList()
            }
        }

        "single object produces one row" {
            val bytes = """[{"id":1,"name":"Alice"}]""".toByteArray(Charsets.UTF_8)
            JsonRowsReader.read(bytes).use {
                it.columns shouldHaveSize 2
                it.columns[0].name shouldBe "id"
                it.columns[0].logicalType shouldBe LogicalType.Int64
                it.columns[1].logicalType shouldBe LogicalType.StringT
                val rows = it.iterator().asSequence().toList()
                rows shouldHaveSize 1
                rows[0][0] shouldBe 1L
                rows[0][1] shouldBe "Alice"
            }
        }

        "mixed null cells handled" {
            val bytes = """[{"a":1,"b":null},{"a":null,"b":"x"}]""".toByteArray(Charsets.UTF_8)
            JsonRowsReader.read(bytes).use {
                val rows = it.iterator().asSequence().toList()
                rows[0][0] shouldBe 1L
                rows[0][1] shouldBe null
                rows[1][0] shouldBe null
                rows[1][1] shouldBe "x"
                it.columns[0].logicalType shouldBe LogicalType.Int64
                it.columns[1].logicalType shouldBe LogicalType.StringT
            }
        }

        "unicode strings round-trip" {
            val bytes = """[{"x":"Příliš žluťoučký kůň"}]""".toByteArray(Charsets.UTF_8)
            JsonRowsReader.read(bytes).use {
                val rows = it.iterator().asSequence().toList()
                rows[0][0] shouldBe "Příliš žluťoučký kůň"
            }
        }

        "boolean inference" {
            val bytes = """[{"flag":true},{"flag":false}]""".toByteArray(Charsets.UTF_8)
            JsonRowsReader.read(bytes).use {
                it.columns[0].logicalType shouldBe LogicalType.Bool
            }
        }

        "double inference for non-integer numbers" {
            val bytes = """[{"x":1.5},{"x":2}]""".toByteArray(Charsets.UTF_8)
            JsonRowsReader.read(bytes).use {
                it.columns[0].logicalType shouldBe LogicalType.Double
            }
        }
    })
