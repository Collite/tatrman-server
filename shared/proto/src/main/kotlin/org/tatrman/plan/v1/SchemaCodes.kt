package org.tatrman.plan.v1

/**
 * Parse a schema-code token (e.g. `"db"`, `"er"`, `"cnc"`, `"ws"`, `"obj"`) into a [SchemaCode]
 * enum value. Case-insensitive. Returns `null` for unknown tokens so callers can decide between
 * a hard failure and a fold to [SchemaCode.SCHEMA_CODE_UNSPECIFIED].
 *
 * The companion of [schemaCodeToToken] — `parseSchemaCode(schemaCodeToToken(x)) == x` for every
 * concrete [SchemaCode] value (and `parseSchemaCode("") == null`).
 */
fun parseSchemaCode(code: String): SchemaCode? =
    when (code.lowercase()) {
        "db" -> SchemaCode.DB
        "er" -> SchemaCode.ER
        "cnc" -> SchemaCode.CNC
        "ws" -> SchemaCode.WS
        "obj" -> SchemaCode.OBJ
        "schema_code_unspecified" -> SchemaCode.SCHEMA_CODE_UNSPECIFIED
        else -> null
    }

/**
 * Render a [SchemaCode] as its lowercase token (`"db"`, `"er"`, `"cnc"`, `"ws"`, `"obj"`).
 * Returns the empty string for [SchemaCode.SCHEMA_CODE_UNSPECIFIED] and the proto-generated
 * `UNRECOGNIZED` value — callers that build dotted paths use [String.takeUnless] / [isNotEmpty]
 * to drop the leading dot.
 */
fun schemaCodeToToken(sc: SchemaCode): String =
    when (sc) {
        SchemaCode.DB -> "db"
        SchemaCode.ER -> "er"
        SchemaCode.CNC -> "cnc"
        SchemaCode.WS -> "ws"
        SchemaCode.OBJ -> "obj"
        SchemaCode.SCHEMA_CODE_UNSPECIFIED -> ""
        else -> ""
    }
