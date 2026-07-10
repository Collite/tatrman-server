package org.tatrman.identity.opa

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class DeepMergeSpec :
    StringSpec({

        fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

        "deepMerge recursively merges nested objects" {
            val main = obj("""{ "a": { "x": 1 }, "b": 2 }""")
            val update = obj("""{ "a": { "y": 3 }, "c": 4 }""")
            deepMerge(main, update) shouldBe obj("""{ "a": { "x": 1, "y": 3 }, "b": 2, "c": 4 }""")
        }

        "update wins on non-object conflicts" {
            val main = obj("""{ "a": 1 }""")
            val update = obj("""{ "a": 2 }""")
            deepMerge(main, update) shouldBe obj("""{ "a": 2 }""")
        }

        "merging an empty update returns main unchanged" {
            val main = obj("""{ "a": { "x": 1 } }""")
            deepMerge(main, JsonObject(emptyMap())) shouldBe main
        }
    })
