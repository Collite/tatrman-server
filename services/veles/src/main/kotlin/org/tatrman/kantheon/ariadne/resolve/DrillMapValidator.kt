package org.tatrman.kantheon.ariadne.resolve

import org.tatrman.kantheon.ariadne.model.DrillMap
import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.kantheon.ariadne.source.LoadWarning

/**
 * v2.2 — validate `def drill_map` blocks against the assembled [Model].
 *
 * Runs after the reconciler has merged all sources, so it can look up the
 * referenced `from` / `to` patterns and inspect their parameters + sourceText.
 *
 * Diagnostic codes (per [contracts §3.2](../../../../../../../feature-new-golem-contracts.md)):
 *
 *  - `DRILL_MAP_UNKNOWN_TARGET`     — `to` references a query that doesn't exist
 *  - `DRILL_MAP_UNKNOWN_SOURCE`     — `from` references a query that doesn't exist
 *  - `DRILL_MAP_UNKNOWN_PARAM`      — an `args` key is not a parameter on `to`
 *  - `DRILL_MAP_MISSING_PARAM`      — a non-optional parameter on `to` has no `args` entry
 *  - `DRILL_MAP_PARAM_NOT_IN_FROM_SQL` — an `args` value (treated as a column name) does
 *      not appear (case-insensitive) in `from.sourceText`. Heuristic — catches typos.
 *
 * Errors are blocking (returned in [errors]); the model is still usable but the
 * drill map will silently no-op at runtime.
 */
class DrillMapValidator(
    private val sourceId: String = "drill-map-validator",
) {
    fun validate(model: Model): ValidationOutput {
        val errors = mutableListOf<LoadWarning>()
        val queries = model.queries

        for (drill in model.drillMaps.values) {
            val fromQuery = queries[drill.fromPattern]
            val toQuery = queries[drill.toPattern]

            if (fromQuery == null) {
                errors +=
                    diag(
                        drill,
                        "DRILL_MAP_UNKNOWN_SOURCE",
                        "'from' pattern ${qnameToPath(drill.fromPattern)} not found",
                    )
            }
            if (toQuery == null) {
                errors +=
                    diag(
                        drill,
                        "DRILL_MAP_UNKNOWN_TARGET",
                        "'to' pattern ${qnameToPath(drill.toPattern)} not found",
                    )
            }

            if (toQuery != null) {
                val knownParams = toQuery.parameters.associateBy { it.name }
                for ((argName, _) in drill.argMapping) {
                    if (argName !in knownParams) {
                        errors +=
                            diag(
                                drill,
                                "DRILL_MAP_UNKNOWN_PARAM",
                                "'$argName' is not a parameter on ${qnameToPath(drill.toPattern)}",
                            )
                    }
                }
                // Missing-required-param check: every param the target declares MUST
                // appear in args. (v1 has no "optional" flag on QueryParameterDef yet, so
                // we treat every declared param as required.)
                for ((paramName, _) in knownParams) {
                    if (paramName !in drill.argMapping) {
                        errors +=
                            diag(
                                drill,
                                "DRILL_MAP_MISSING_PARAM",
                                "required parameter '$paramName' of ${qnameToPath(
                                    drill.toPattern,
                                )} is not mapped in args",
                            )
                    }
                }
            }

            if (fromQuery != null) {
                val sourceText = fromQuery.sourceText
                for ((_, sourceExpr) in drill.argMapping) {
                    // Quoted literal — accept as-is (anything between quotes is intentional).
                    if (sourceExpr.startsWith("'") && sourceExpr.endsWith("'")) continue
                    if (sourceExpr.startsWith("\"") && sourceExpr.endsWith("\"")) continue
                    // Numeric literal — accept.
                    if (sourceExpr.toDoubleOrNull() != null) continue
                    // Heuristic: bare identifier must appear (case-insensitive) somewhere
                    // in `from.sourceText`. The SQL may reference it as `t.IDUCETZAP`, so
                    // a substring match is sufficient.
                    if (!sourceText.contains(sourceExpr, ignoreCase = true)) {
                        errors +=
                            diag(
                                drill,
                                "DRILL_MAP_PARAM_NOT_IN_FROM_SQL",
                                "'$sourceExpr' not found in 'from' pattern's SQL — typo?",
                            )
                    }
                }
            }
        }

        return ValidationOutput(errors = errors)
    }

    private fun diag(
        drill: DrillMap,
        code: String,
        msg: String,
    ): LoadWarning =
        LoadWarning(
            sourceId = sourceId,
            file = drill.sourceFile,
            line = -1,
            column = -1,
            message = "$code: ${qnameToPath(drill.qname)}: $msg",
        )

    private fun qnameToPath(qn: org.tatrman.plan.v1.QualifiedName): String {
        val pkg = if (qn.`package`.isNotEmpty()) "${qn.`package`}." else ""
        // SchemaCode UNSPECIFIED covers query/map/etc.; render the namespace as
        // the schema prefix in that case (drill maps land here, in query.drill.*).
        val schemaLabel =
            if (qn.schemaCode == org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED) {
                qn.namespace
            } else {
                "${qn.schemaCode.name.lowercase()}.${qn.namespace}"
            }
        return "$pkg$schemaLabel.${qn.name}"
    }

    data class ValidationOutput(
        val errors: List<LoadWarning>,
    )
}
