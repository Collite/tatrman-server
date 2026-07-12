// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.mcp

import org.tatrman.plan.v1.Warning
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Convert [Warning] protos accumulated on `PipelineContext.warnings` into
 * the agent-facing JSON entry shape:
 *
 * ```
 * { code, severity, text, sourceService, metadata }
 * ```
 *
 * **Severity.** The current `Warning` proto (org.tatrman.plan.v1.Warning)
 * does not carry a severity field; the 2.1.B plan assumed it did. Until the
 * proto evolves we derive severity from a small known-codes table and
 * default to `warn`. Documented in tools/query-mcp/docs/technical/error-codes.md.
 *
 * **Metadata.** The proto has no metadata map either. We surface `source_stage`
 * via `metadata.sourceStage` — the only structured side-channel the proto
 * actually carries — so agents have at least the stage info.
 */
internal object PipelineWarnings {
    /** Codes the platform considers informational (audit-trail rather than caution). */
    private val INFO_CODES =
        setOf(
            "security_predicate_applied",
            "sticky_session_match",
            "routing_decision",
        )

    /** Codes the platform considers errors even though the proto carries no severity. */
    private val ERROR_CODES = setOf<String>()

    fun toJsonArray(warnings: List<Warning>): JsonArray =
        buildJsonArray {
            for (w in warnings) {
                add(
                    buildJsonObject {
                        put("code", JsonPrimitive(w.code))
                        put("severity", JsonPrimitive(deriveSeverity(w.code)))
                        put("text", JsonPrimitive(w.message))
                        put("sourceService", JsonPrimitive(w.sourceService))
                        put(
                            "metadata",
                            buildJsonObject {
                                if (w.sourceStage.isNotEmpty()) {
                                    put("sourceStage", JsonPrimitive(w.sourceStage))
                                }
                            },
                        )
                    },
                )
            }
        }

    private fun deriveSeverity(code: String): String =
        when {
            code in INFO_CODES -> "info"
            code in ERROR_CODES -> "error"
            else -> "warn"
        }
}
