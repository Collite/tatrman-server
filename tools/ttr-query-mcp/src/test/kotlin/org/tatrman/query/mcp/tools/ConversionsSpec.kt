package org.tatrman.query.mcp.tools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ConversionsSpec :
    StringSpec({

        // ---- Typed form ({value, type}) ----------------------------------------

        "typed varchar → text tag, string value preserved verbatim" {
            val args =
                buildJsonObject {
                    put(
                        "obdobi",
                        buildJsonObject {
                            put("value", JsonPrimitive("2026.04"))
                            put("type", JsonPrimitive("varchar"))
                        },
                    )
                }
            val bindings = parametersToBindings(args)
            bindings.size shouldBe 1
            val b = bindings.first()
            b.name shouldBe "obdobi"
            b.type shouldBe "text"
            b.value.stringValue shouldBe "2026.04"
        }

        "typed varchar keeps numeric-looking code as text (fidelity win over inference)" {
            val args =
                buildJsonObject {
                    put(
                        "kod_uctu",
                        buildJsonObject {
                            put("value", JsonPrimitive("518032"))
                            put("type", JsonPrimitive("varchar"))
                        },
                    )
                }
            val bindings = parametersToBindings(args)
            val b = bindings.first()
            b.type shouldBe "text"
            b.value.stringValue shouldBe "518032"
            // Must NOT be parsed as an integer
            b.value.hasIntValue() shouldBe false
        }

        "typed int → int tag, value parsed as long" {
            val args =
                buildJsonObject {
                    put(
                        "id",
                        buildJsonObject {
                            put("value", JsonPrimitive(12345))
                            put("type", JsonPrimitive("int"))
                        },
                    )
                }
            val bindings = parametersToBindings(args)
            val b = bindings.first()
            b.type shouldBe "int"
            b.value.intValue shouldBe 12345L
        }

        "typed date → datetime tag, string value" {
            val args =
                buildJsonObject {
                    put(
                        "den",
                        buildJsonObject {
                            put("value", JsonPrimitive("2026-04-01"))
                            put("type", JsonPrimitive("date"))
                        },
                    )
                }
            val bindings = parametersToBindings(args)
            val b = bindings.first()
            b.type shouldBe "datetime"
            b.value.datetimeValue shouldBe "2026-04-01"
        }

        "typed bool → bool tag" {
            val args =
                buildJsonObject {
                    put(
                        "active",
                        buildJsonObject {
                            put("value", JsonPrimitive(true))
                            put("type", JsonPrimitive("boolean"))
                        },
                    )
                }
            val bindings = parametersToBindings(args)
            val b = bindings.first()
            b.type shouldBe "bool"
            b.value.boolValue shouldBe true
        }

        "typed float → float tag" {
            val args =
                buildJsonObject {
                    put(
                        "rate",
                        buildJsonObject {
                            put("value", JsonPrimitive(3.14))
                            put("type", JsonPrimitive("decimal"))
                        },
                    )
                }
            val bindings = parametersToBindings(args)
            val b = bindings.first()
            b.type shouldBe "float"
        }

        // ---- Bare form back-compat (regression guard) -------------------------

        "bare string → inferred text" {
            val args = buildJsonObject { put("obdobi", JsonPrimitive("2026.04")) }
            val b = parametersToBindings(args).first()
            b.type shouldBe "text"
            b.value.stringValue shouldBe "2026.04"
        }

        "bare integer → inferred int" {
            val args = buildJsonObject { put("id", JsonPrimitive(12345)) }
            val b = parametersToBindings(args).first()
            b.type shouldBe "int"
            b.value.intValue shouldBe 12345L
        }

        "bare boolean → inferred bool" {
            val args = buildJsonObject { put("flag", JsonPrimitive(true)) }
            val b = parametersToBindings(args).first()
            b.type shouldBe "bool"
            b.value.boolValue shouldBe true
        }

        "bare ISO date string → inferred datetime" {
            val args = buildJsonObject { put("den", JsonPrimitive("2026-04-01")) }
            val b = parametersToBindings(args).first()
            b.type shouldBe "datetime"
            b.value.datetimeValue shouldBe "2026-04-01"
        }

        // ---- Typed-form discrimination edge cases -----------------------------

        "object with a 'value' key but NO 'type' is treated as a bare value, not the typed envelope" {
            // A non-golem caller may pass a structured object as a bare parameter;
            // it must NOT be mistaken for {value,type} and silently unwrapped.
            val args =
                buildJsonObject {
                    put(
                        "cfg",
                        buildJsonObject {
                            put("value", JsonPrimitive(42))
                            put("extra", JsonPrimitive(1))
                        },
                    )
                }
            val b = parametersToBindings(args).first()
            // Bare path → JSON text of the whole object, with 'extra' preserved.
            b.type shouldBe "text"
            b.value.stringValue.contains("extra") shouldBe true
        }

        "typed form with null value → is_null binding" {
            val args =
                buildJsonObject {
                    put(
                        "stred",
                        buildJsonObject {
                            put("value", kotlinx.serialization.json.JsonNull)
                            put("type", JsonPrimitive("varchar"))
                        },
                    )
                }
            val b = parametersToBindings(args).first()
            b.type shouldBe "text"
            b.value.isNull shouldBe true
        }
    })
