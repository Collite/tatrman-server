package org.tatrman.kantheon.theseus.mcp.tools

import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.Value
import org.tatrman.proteus.v1.Language
import org.tatrman.proteus.v1.SqlDialect
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// MCP-tool-arg → proto enum/binding conversions. Separated from the tool
// itself so they're trivially unit-testable and reusable across `query`
// and `compile`.

/** Tool-arg source-language string → translator proto enum. */
internal fun parseSourceLanguage(raw: String?): Language? =
    when (raw?.lowercase()) {
        null, "" -> null
        "sql" -> Language.SQL
        "transformation_dsl", "transformation-dsl", "transdsl" -> Language.TRANSFORMATION_DSL
        "dataframe_dsl", "dataframe-dsl", "dfdsl" -> Language.DATAFRAME_DSL
        "rel_node", "relnode" -> Language.REL_NODE
        else -> null
    }

/** Tool-arg dialect string → translator proto enum. */
internal fun parseSqlDialect(raw: String?): SqlDialect? =
    when (raw?.lowercase()) {
        null, "" -> null
        "mssql" -> SqlDialect.MSSQL
        "postgresql", "postgres" -> SqlDialect.POSTGRESQL
        "mysql_mariadb", "mysql", "mariadb" -> SqlDialect.MYSQL_MARIADB
        else -> null
    }

/**
 * Convert an MCP-tool-arg parameters map (`{"name": jsonValue}`) into the
 * proto ParameterBinding list expected by RunRequest.context.parameters.
 *
 * Accepts two forms per entry (back-compatible):
 *   * **Bare**: `JsonPrimitive | JsonNull` → type inferred from the JSON value
 *     (existing behaviour unchanged).
 *   * **Typed**: `JsonObject { "value": <scalar|null>, "type": "<surface_type>" }`
 *     → declared type wins; `value` coerced to that tag. Surface-type vocabulary:
 *     varchar/char/text→text, int/integer/bigint→int, decimal/numeric/float/double→float,
 *     bool/boolean→bool, date/datetime/timestamp→datetime.
 *
 * Bare-form inference (unchanged):
 *   * JsonNull → "text", is_null=true
 *   * JsonPrimitive(boolean) → "bool"
 *   * JsonPrimitive(number, integral) → "int"
 *   * JsonPrimitive(number, fractional) → "float"
 *   * JsonPrimitive(string) → "datetime" if ISO-8601, else "text"
 *   * Anything else → "text" via toString()
 */
internal fun parametersToBindings(args: Map<String, JsonElement>): List<ParameterBinding> =
    args.map { (name, el) ->
        // Typed form is `{value, type}` — require BOTH keys so a bare parameter
        // that happens to be an object with a `value` field (a non-golem caller
        // passing a structured value) is not mis-read as the typed envelope.
        val (typeTag, value) =
            if (el is JsonObject && el.containsKey("value") && el.containsKey("type")) {
                typedParameterTypeAndValue(el)
            } else {
                inferParameterTypeAndValue(el)
            }
        ParameterBinding
            .newBuilder()
            .setName(name)
            .setType(typeTag)
            .setValue(value)
            .build()
    }

/**
 * Surface DSL type string → parameters.proto tag. Lives in one place so both
 * the typed-form path and external callers use the same mapping.
 */
private fun surfaceTypeToTag(t: String): String =
    when (t.trim().lowercase()) {
        "varchar", "char", "text", "string", "nvarchar" -> "text"
        "int", "integer", "bigint", "smallint", "tinyint" -> "int"
        "decimal", "numeric", "float", "double", "real" -> "float"
        "bool", "boolean", "bit" -> "bool"
        "date", "datetime", "timestamp" -> "datetime"
        else -> "text"
    }

/** Build (tag, Value) for the typed `{value, type}` form. */
private fun typedParameterTypeAndValue(obj: JsonObject): Pair<String, Value> {
    val rawType = (obj["type"] as? JsonPrimitive)?.content ?: ""
    val tag = surfaceTypeToTag(rawType)
    val rawValue = obj["value"] ?: JsonNull
    if (rawValue is JsonNull) {
        return tag to Value.newBuilder().setIsNull(true).build()
    }
    val content = (rawValue as? JsonPrimitive)?.content ?: rawValue.toString()
    val value =
        when (tag) {
            "int" -> {
                val l = content.toLongOrNull() ?: content.toDoubleOrNull()?.toLong()
                if (l != null) {
                    Value.newBuilder().setIntValue(l).build()
                } else {
                    Value.newBuilder().setStringValue(content).build()
                }
            }
            "float" -> {
                val d = content.toDoubleOrNull()
                if (d != null) {
                    Value.newBuilder().setFloatValue(d).build()
                } else {
                    Value.newBuilder().setStringValue(content).build()
                }
            }
            "bool" -> {
                val b = content.toBooleanStrictOrNull()
                if (b != null) {
                    Value.newBuilder().setBoolValue(b).build()
                } else {
                    Value.newBuilder().setStringValue(content).build()
                }
            }
            "datetime" -> Value.newBuilder().setDatetimeValue(content).build()
            else -> Value.newBuilder().setStringValue(content).build()
        }
    return tag to value
}

private fun inferParameterTypeAndValue(el: JsonElement): Pair<String, Value> {
    if (el is JsonNull) {
        return "text" to Value.newBuilder().setIsNull(true).build()
    }
    if (el is JsonPrimitive) {
        if (!el.isString) {
            // numeric / boolean primitive
            val asBool = el.content.toBooleanStrictOrNull()
            if (asBool != null) {
                return "bool" to Value.newBuilder().setBoolValue(asBool).build()
            }
            val asLong = el.content.toLongOrNull()
            if (asLong != null) {
                return "int" to Value.newBuilder().setIntValue(asLong).build()
            }
            val asDouble = el.content.toDoubleOrNull()
            if (asDouble != null) {
                return "float" to Value.newBuilder().setFloatValue(asDouble).build()
            }
        }
        // string-like
        val s = el.content
        return if (looksLikeIsoDateTime(s)) {
            "datetime" to Value.newBuilder().setDatetimeValue(s).build()
        } else {
            "text" to Value.newBuilder().setStringValue(s).build()
        }
    }
    // arrays / objects fall back to JSON text
    return "text" to Value.newBuilder().setStringValue(el.toString()).build()
}

private fun looksLikeIsoDateTime(s: String): Boolean {
    if (s.length < 10) return false
    // YYYY-MM-DD or YYYY-MM-DDTHH:MM…
    val datePart = s.substring(0, 10)
    if (!datePart.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return false
    if (s.length == 10) return true
    val sep = s[10]
    return sep == 'T' || sep == ' '
}

/**
 * Reads the `arguments` JsonObject from an MCP CallToolRequest. The MCP SDK
 * exposes arguments as a `JsonObject?`; absent → empty.
 */
internal fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
