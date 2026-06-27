package shared.formatter.output

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import shared.formatter.core.ColumnMeta
import shared.formatter.core.FormatOptions

/**
 * Phase 2.2 — small helpers shared by the four writers for header
 * localisation and value-label substitution.
 *
 * The contract is:
 *   * Header: prefer `displayLabel.preferred(opts.preferredLanguage)`;
 *     fall back to the bare column `name`.
 *   * Cell:   if `opts.substituteValueLabels` and the rendered cell text
 *     is a key in `valueLabels`, substitute the localised label;
 *     otherwise return the original value.
 *
 * Substitution operates on the *rendered text* form so numeric codes
 * (`1`, `2`, `3`) and string codes (`"A"`, `"B"`) share a uniform shape
 * — same substitution rule, regardless of column logical type.
 */
internal object Localisation {
    fun headerFor(
        col: ColumnMeta,
        opts: FormatOptions,
    ): String = col.displayLabel.preferred(opts.preferredLanguage) ?: col.name

    /** Apply value-label substitution to text cells (markdown / csv / tsv). */
    fun substituteText(
        rendered: String,
        col: ColumnMeta,
        opts: FormatOptions,
    ): String {
        if (!opts.substituteValueLabels) return rendered
        val labeled = col.valueLabels[rendered] ?: return rendered
        return labeled.preferred(opts.preferredLanguage) ?: rendered
    }

    /**
     * Apply value-label substitution to a JSON cell. Substituted cells become
     * `JsonPrimitive(labelText)`; non-substituted cells pass through unchanged.
     * Non-primitive cells (objects, arrays, null) are never substituted.
     */
    fun substituteJson(
        rendered: JsonElement,
        col: ColumnMeta,
        opts: FormatOptions,
    ): JsonElement {
        if (!opts.substituteValueLabels) return rendered
        if (rendered !is JsonPrimitive) return rendered
        val key = rendered.contentOrNull ?: return rendered
        val labeled = col.valueLabels[key] ?: return rendered
        val text = labeled.preferred(opts.preferredLanguage) ?: return rendered
        return JsonPrimitive(text)
    }
}
