// SPDX-License-Identifier: Apache-2.0
package shared.formatter.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import shared.formatter.core.ColumnMeta
import shared.formatter.core.InMemoryRowIterable
import shared.formatter.core.RowIterable
import shared.formatter.types.LogicalType

/**
 * Reads `[ {col: val, ...}, ... ]` JSON-rows arrays.
 *
 * Used by the legacy `sql-formatter` migration path, where the caller
 * already knows its schema. Logical type per column is best-effort:
 * inferred from the first non-null value across rows; mixed columns
 * fall back to STRING.
 */
internal object JsonRowsReader {
    private val parser =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

    fun read(bytes: ByteArray): RowIterable {
        if (bytes.isEmpty()) return InMemoryRowIterable(columns = emptyList(), rows = emptyList())
        val root = parser.parseToJsonElement(String(bytes, Charsets.UTF_8))
        require(root is JsonArray) { "JSON_ROWS input must be a top-level array, got ${root::class.simpleName}" }
        if (root.isEmpty()) return InMemoryRowIterable(columns = emptyList(), rows = emptyList())

        // Column union (order-preserving) and per-column inferred LogicalType.
        val columnNames = linkedSetOf<String>()
        for (el in root) {
            require(el is JsonObject) { "JSON_ROWS rows must be objects, got ${el::class.simpleName}" }
            columnNames.addAll(el.keys)
        }
        val orderedNames = columnNames.toList()
        val typeByCol =
            orderedNames.associateWith { name ->
                inferType(root.mapNotNull { (it as JsonObject)[name] })
            }
        val columns =
            orderedNames.map { name ->
                ColumnMeta(name = name, logicalType = typeByCol.getValue(name), nullable = true)
            }

        val rows =
            root.map { el ->
                val obj = el as JsonObject
                Array<Any?>(orderedNames.size) { i ->
                    val cell = obj[orderedNames[i]]
                    nativeOf(cell, typeByCol.getValue(orderedNames[i]))
                }
            }
        return InMemoryRowIterable(columns = columns, rows = rows)
    }

    private fun inferType(values: List<JsonElement>): LogicalType {
        var seenNonNull = false
        var allLong = true
        var allNumeric = true
        var allBool = true
        for (v in values) {
            if (v is JsonNull) continue
            seenNonNull = true
            if (v !is JsonPrimitive) {
                // arrays / objects → string-ish; we don't model them as columns.
                return LogicalType.StringT
            }
            if (v.isString) {
                allLong = false
                allNumeric = false
                allBool = false
                continue
            }
            // Non-string primitive — try long first, then double, then bool.
            if (v.content.toLongOrNull() == null) allLong = false
            if (v.content.toDoubleOrNull() == null) allNumeric = false
            if (v.content != "true" && v.content != "false") allBool = false
        }
        if (!seenNonNull) return LogicalType.NullType
        if (allBool) return LogicalType.Bool
        if (allLong) return LogicalType.Int64
        if (allNumeric) return LogicalType.Double
        return LogicalType.StringT
    }

    private fun nativeOf(
        el: JsonElement?,
        type: LogicalType,
    ): Any? {
        if (el == null || el is JsonNull) return null
        if (el !is JsonPrimitive) return el.toString()
        if (el.isString) return el.content
        return when (type) {
            LogicalType.Int64 -> el.content.toLong()
            LogicalType.Double -> el.content.toDouble()
            LogicalType.Bool -> el.content.toBoolean()
            else -> el.content
        }
    }
}
