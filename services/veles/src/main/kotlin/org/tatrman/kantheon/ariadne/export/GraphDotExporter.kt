package org.tatrman.kantheon.ariadne.export

import org.tatrman.kantheon.ariadne.graph.ModelGraph

/**
 * Phase 09 B4 / DF-M15 — Graphviz DOT export of a [ModelGraph].
 *
 * Output shape:
 * ```
 * digraph metadata_model {
 *   "<internalId>" [label="qname-or-id", shape=box, style="filled", fillcolor="<by-kind>"];
 *   ...
 *   "<src>" -> "<tgt>" [label="DEFINES", color="<by-edge>"];
 *   ...
 * }
 * ```
 *
 * Vertex labels prefer the object's qualified name (`schemaCode.namespace.name`) when known,
 * falling back to the internal id. Vertex colour is keyed by the model kind tag so a quick
 * visual scan separates tables, columns, entities, attributes, queries, etc. Edge colour is
 * keyed by [org.tatrman.kantheon.ariadne.graph.EdgeType] (DEFINES / REFERENCES / MAPS_TO / USES).
 *
 * Identifiers are DOT-escaped — double quotes and backslashes are quoted; everything else
 * goes through verbatim. v1 model identifiers don't contain control characters so this is
 * sufficient.
 */
object GraphDotExporter {
    private const val DEFAULT_FILL = "#eeeeee"

    private val kindFill: Map<String, String> =
        mapOf(
            "table" to "#c8e6c9",
            "column" to "#a5d6a7",
            "entity" to "#bbdefb",
            "attribute" to "#90caf9",
            "query" to "#ffe082",
            "er2db_entity" to "#ce93d8",
            "er2db_attribute" to "#b39ddb",
            "er2db_relation" to "#9fa8da",
            "fk" to "#ffab91",
        )

    private val edgeColour: Map<String, String> =
        mapOf(
            "DEFINES" to "#388e3c",
            "REFERENCES" to "#f57c00",
            "MAPS_TO" to "#7b1fa2",
            "USES" to "#1976d2",
        )

    fun export(graph: ModelGraph): String {
        val sb = StringBuilder()
        sb.append("digraph metadata_model {\n")
        sb.append("  rankdir=LR;\n")
        sb.append("  node [shape=box, style=\"filled,rounded\", fontname=\"Helvetica\"];\n")
        sb.append("  edge [fontname=\"Helvetica\", fontsize=10];\n\n")

        // Vertices.
        for ((internalId, obj) in graph.byInternalId) {
            val kind = obj.kindTag()
            val label = labelFor(internalId, dotted(obj.qname), kind)
            val fill = kindFill[kind] ?: DEFAULT_FILL
            sb.append("  ")
            sb.append(quote(internalId))
            sb.append(" [label=")
            sb.append(quote(label))
            sb.append(", fillcolor=\"")
            sb.append(fill)
            sb.append("\"];\n")
        }
        sb.append('\n')

        // Edges.
        graph.forEachEdge { edge ->
            val colour = edgeColour[edge.type.name] ?: "#666666"
            sb.append("  ")
            sb.append(quote(edge.source))
            sb.append(" -> ")
            sb.append(quote(edge.target))
            sb.append(" [label=\"")
            sb.append(edge.type.name)
            sb.append("\", color=\"")
            sb.append(colour)
            sb.append("\"];\n")
        }

        sb.append("}\n")
        return sb.toString()
    }

    private fun labelFor(
        internalId: String,
        qnameDotted: String,
        kindTag: String,
    ): String {
        val head = qnameDotted.ifEmpty { internalId }
        return "$head\n[$kindTag]"
    }

    private fun dotted(q: org.tatrman.plan.v1.QualifiedName): String {
        val parts =
            listOfNotNull(
                org.tatrman.plan.v1
                    .schemaCodeToToken(q.schemaCode)
                    .takeUnless { it.isEmpty() },
                q.namespace.takeUnless { it.isEmpty() },
                q.name.takeUnless { it.isEmpty() },
            )
        return parts.joinToString(".")
    }

    /** DOT identifier quoting — `"` → `\"`, `\` → `\\`. Newlines become `\n` for label rendering. */
    private fun quote(raw: String): String {
        val escaped =
            buildString(raw.length + 2) {
                append('"')
                for (ch in raw) {
                    when (ch) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        else -> append(ch)
                    }
                }
                append('"')
            }
        return escaped
    }
}

/** Best-effort kind tag for colour grouping. `ModelObject` carries the tag via its concrete subtype. */
private fun org.tatrman.kantheon.ariadne.model.ModelObject.kindTag(): String =
    when (this) {
        is org.tatrman.kantheon.ariadne.model.DbTable -> "table"
        is org.tatrman.kantheon.ariadne.model.DbView -> "view"
        is org.tatrman.kantheon.ariadne.model.DbColumn -> "column"
        is org.tatrman.kantheon.ariadne.model.DbProcedure -> "procedure"
        is org.tatrman.kantheon.ariadne.model.DbForeignKey -> "fk"
        is org.tatrman.kantheon.ariadne.model.Entity -> "entity"
        is org.tatrman.kantheon.ariadne.model.Attribute -> "attribute"
        is org.tatrman.kantheon.ariadne.model.Relation -> "relation"
        is org.tatrman.kantheon.ariadne.model.Role -> "role"
        is org.tatrman.kantheon.ariadne.model.Er2DbEntityMapping -> "er2db_entity"
        is org.tatrman.kantheon.ariadne.model.Er2DbAttributeMapping -> "er2db_attribute"
        is org.tatrman.kantheon.ariadne.model.Er2DbRelationMapping -> "er2db_relation"
        is org.tatrman.kantheon.ariadne.model.Er2CncRoleMapping -> "er2cnc_role"
        is org.tatrman.kantheon.ariadne.model.Query -> "query"
        is org.tatrman.kantheon.ariadne.model.DrillMap -> "drill_map"
    }
