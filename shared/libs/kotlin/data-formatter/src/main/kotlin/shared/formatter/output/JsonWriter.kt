// SPDX-License-Identifier: Apache-2.0
package shared.formatter.output

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import shared.formatter.core.FormatOptions
import shared.formatter.core.RowIterable
import shared.formatter.types.ValueRenderer

/**
 * Default shape: top-level JSON array of objects: `[{"col":val,...},...]`.
 *
 * **Key-order contract** (DF-F04 / Phase 08 D4) — object keys are emitted in
 * column-declaration order, and `kotlinx.serialization.buildJsonObject` preserves
 * insertion order. When `RowNumbering.ONE_BASED` is on, `Projection.rowNumbering`
 * prepends a `#` `ColumnMeta` at position 0 — so the `#` key is always the FIRST
 * key in each row object. Consumers that rely on positional dispatch (some
 * stream-readers, schema-less parsers) can count on this.
 *
 * Phase 2.2 — when `opts.includeColumnLabels` is true, the shape becomes
 * `{"rows": [...], "__columnLabels": {col: "<localised>", ...}}`. Object
 * *keys* always remain raw column names so JSON consumers can keep stable
 * key-based dispatch; the localised labels live in the side-car
 * `__columnLabels` object.
 *
 * Cell substitution (when `opts.substituteValueLabels`) replaces the raw
 * value with `JsonPrimitive(label)` — the type stays JSON-string after
 * substitution regardless of the original cell type.
 */
internal object JsonWriter {
    private val json = Json { prettyPrint = false }

    fun write(
        rows: RowIterable,
        opts: FormatOptions,
    ): WriteOutcome {
        val cols = rows.columns
        var written = 0
        var truncated = false
        val limit = opts.rowLimit
        val it = rows.iterator()
        val arr: JsonArray =
            buildJsonArray {
                while (it.hasNext()) {
                    if (limit != null && written >= limit) {
                        if (!opts.truncateSilently) error("rowLimit ($limit) exceeded")
                        truncated = true
                        break
                    }
                    val row = it.next()
                    val obj: JsonObject =
                        buildJsonObject {
                            for (i in cols.indices) {
                                val col = cols[i]
                                val rendered = ValueRenderer.renderForJson(row[i], col.logicalType, opts)
                                put(col.name, Localisation.substituteJson(rendered, col, opts))
                            }
                        }
                    add(obj)
                    written++
                }
            }

        val rootElement: JsonElement =
            if (opts.includeColumnLabels) {
                buildJsonObject {
                    put("rows", arr)
                    put("__columnLabels", columnLabelsObject(cols, opts))
                }
            } else {
                arr
            }

        val bytes = json.encodeToString(JsonElement.serializer(), rootElement).toByteArray(Charsets.UTF_8)
        return WriteOutcome(bytes = bytes, rowsWritten = written, truncated = truncated, columns = cols)
    }

    private fun columnLabelsObject(
        cols: List<shared.formatter.core.ColumnMeta>,
        opts: FormatOptions,
    ): JsonObject =
        buildJsonObject {
            cols.forEach { col -> put(col.name, JsonPrimitive(Localisation.headerFor(col, opts))) }
        }
}
