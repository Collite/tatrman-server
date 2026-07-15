// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.conformance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * LG-P0·S2·T5 — the contract-diff engine. Structurally compares a captured 1.x response envelope
 * against a 2.0 one and reports field-level deltas: keys added, keys removed, scalar values changed,
 * array shapes changed. Structure is what is contractual; volatile identity (`id`, `created`,
 * `system_fingerprint`) and the model's generated text are ignored so a real behavioural delta is not
 * drowned by run-to-run noise.
 *
 * This is the tested authority. The live replay (capture 1.x + 2.0 pairs, then `diff`) is a manual
 * procedure documented in `contract-diff/README.md`, and re-uses this exact logic via the
 * `-Dcontract.diff.base`-gated replay — no second, untested implementation (deviates deliberately from
 * the task's `diff.main.kts`; recorded in S2 findings).
 */
object ContractDiff {
    /** Leaf keys whose values are volatile identity/telemetry — removed from both sides before diffing. */
    val VOLATILE_KEYS = setOf("id", "created", "created_at", "createdAt", "system_fingerprint")

    /**
     * Path suffixes whose scalar VALUE is model-generated and therefore not compared — presence and
     * type are still checked. (The path grammar is `$.a.b[i].c`.)
     */
    val VALUE_BLIND_SUFFIXES = setOf(".message.content", ".delta.content", ".content")

    data class Delta(
        val path: String,
        val kind: Kind,
        val left: String?,
        val right: String?,
    ) {
        enum class Kind { KEY_ADDED, KEY_REMOVED, VALUE_CHANGED, TYPE_CHANGED, ARRAY_SIZE }

        override fun toString() = "$kind at $path: ${left ?: "∅"} -> ${right ?: "∅"}"
    }

    fun diff(
        left: JsonElement,
        right: JsonElement,
        volatileKeys: Set<String> = VOLATILE_KEYS,
        valueBlindSuffixes: Set<String> = VALUE_BLIND_SUFFIXES,
    ): List<Delta> {
        val deltas = mutableListOf<Delta>()
        walk("$", left, right, volatileKeys, valueBlindSuffixes, deltas)
        return deltas
    }

    private fun walk(
        path: String,
        left: JsonElement,
        right: JsonElement,
        volatileKeys: Set<String>,
        valueBlindSuffixes: Set<String>,
        out: MutableList<Delta>,
    ) {
        when {
            left is JsonObject && right is JsonObject -> {
                val keys = (left.keys + right.keys).filter { it !in volatileKeys }
                for (k in keys) {
                    val l = left[k]
                    val r = right[k]
                    val childPath = "$path.$k"
                    when {
                        l == null -> out += Delta(childPath, Delta.Kind.KEY_ADDED, null, typeOf(r!!))
                        r == null -> out += Delta(childPath, Delta.Kind.KEY_REMOVED, typeOf(l), null)
                        else -> walk(childPath, l, r, volatileKeys, valueBlindSuffixes, out)
                    }
                }
            }
            left is JsonArray && right is JsonArray -> {
                if (left.size != right.size) {
                    out += Delta(path, Delta.Kind.ARRAY_SIZE, left.size.toString(), right.size.toString())
                }
                for (i in 0 until minOf(left.size, right.size)) {
                    walk("$path[$i]", left[i], right[i], volatileKeys, valueBlindSuffixes, out)
                }
            }
            left is JsonPrimitive && right is JsonPrimitive -> {
                if (valueBlindSuffixes.any { path.endsWith(it) }) return // model-generated text — ignore value
                if (left.isString != right.isString) {
                    out += Delta(path, Delta.Kind.TYPE_CHANGED, typeOf(left), typeOf(right))
                } else if (left.content != right.content) {
                    out += Delta(path, Delta.Kind.VALUE_CHANGED, left.content, right.content)
                }
            }
            else -> out += Delta(path, Delta.Kind.TYPE_CHANGED, typeOf(left), typeOf(right))
        }
    }

    private fun typeOf(e: JsonElement): String =
        when (e) {
            is JsonNull -> "null"
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonPrimitive -> if (e.isString) "string" else "literal"
        }

    /** Render a delta list as a markdown report (empty list → the "clean" line). */
    fun report(
        label: String,
        deltas: List<Delta>,
    ): String =
        if (deltas.isEmpty()) {
            "### $label\n\n✅ no contractual deltas\n"
        } else {
            buildString {
                append("### $label\n\n")
                append("| kind | path | 1.x | 2.0 |\n|---|---|---|---|\n")
                deltas.forEach { append("| ${it.kind} | `${it.path}` | ${it.left ?: "∅"} | ${it.right ?: "∅"} |\n") }
            }
        }
}
