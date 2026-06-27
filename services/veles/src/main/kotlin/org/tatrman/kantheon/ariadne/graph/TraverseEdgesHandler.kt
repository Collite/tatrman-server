package org.tatrman.kantheon.ariadne.graph

import org.tatrman.ariadne.v1.Direction as ProtoDirection
import org.tatrman.ariadne.v1.EdgeType as ProtoEdgeType
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.kantheon.ariadne.model.Attribute
import org.tatrman.kantheon.ariadne.model.AttributeMappingTarget
import org.tatrman.kantheon.ariadne.model.DbColumn
import org.tatrman.kantheon.ariadne.model.DbForeignKey
import org.tatrman.kantheon.ariadne.model.DbTable
import org.tatrman.kantheon.ariadne.model.DbView
import org.tatrman.kantheon.ariadne.model.Entity
import org.tatrman.kantheon.ariadne.model.Er2CncRoleMapping
import org.tatrman.kantheon.ariadne.model.Er2DbAttributeMapping
import org.tatrman.kantheon.ariadne.model.Er2DbEntityMapping
import org.tatrman.kantheon.ariadne.model.Er2DbRelationMapping
import org.tatrman.kantheon.ariadne.model.MappingTarget
import org.tatrman.kantheon.ariadne.model.Model
import java.util.ArrayDeque

/**
 * DF-M03 / Phase 07 B1 — typed-edge graph keyed on `QualifiedName`, used by the user-facing
 * `TraverseEdges` RPC.
 *
 * Distinct from [ModelGraph] (the JGraphT-backed graph keyed on `internalId`, used for
 * cycle/topology/connectivity analysis): this index routes *through* mapping vertices, so
 * traversal from `er.entity.sales` directly yields the mapped table/role without an intermediate
 * mapping hop the agent doesn't care about.
 *
 * Edge catalog matches the [ProtoEdgeType] enum:
 *   - **`DEFINES`** — `DbColumn → DbTable/DbView`; `Attribute → Entity`.
 *   - **`REFERENCES`** — `DbColumn → DbColumn` per `DbForeignKey.{fromColumns[i], toColumns[i]}` pair.
 *   - **`MAPS_TO`** — bypasses mapping vertices: `Er2DbEntityMapping.entity → target qname`;
 *     `Er2DbAttributeMapping.attribute → column qname` (Expression targets are skipped, no qname);
 *     `Er2DbRelationMapping.relation → foreign_key qname`; `Er2CncRoleMapping.entity → role qname`.
 *   - **`USES`** — `Query → object_referenced_qname`. Slot wired but currently empty (`Query.uses`
 *     tracking is DF-T03 follow-up).
 *
 * BFS-traverses to [maxDepth] (clamped to 10 server-side); cycles handled by the standard
 * visited-set; respects [ProtoDirection] (OUTGOING / INCOMING / BOTH).
 */
internal class TraverseEdgesHandler(
    model: Model,
) {
    private val outgoing: Map<QualifiedName, List<Edge>> = buildOutgoing(model)
    private val incoming: Map<QualifiedName, List<Edge>> by lazy {
        val out = mutableMapOf<QualifiedName, MutableList<Edge>>()
        for (edges in outgoing.values) {
            for (e in edges) out.getOrPut(e.target) { mutableListOf() }.add(e)
        }
        out
    }

    data class Edge(
        val type: ProtoEdgeType,
        val source: QualifiedName,
        val target: QualifiedName,
    )

    data class Step(
        val edge: Edge,
        val depth: Int,
    )

    fun traverse(
        from: QualifiedName,
        edgeTypes: Set<ProtoEdgeType>,
        direction: ProtoDirection,
        maxDepth: Int,
    ): List<Step> {
        if (maxDepth <= 0) return emptyList()
        val cap = maxDepth.coerceAtMost(MAX_DEPTH_CAP)
        val effectiveDir =
            if (direction == ProtoDirection.DIRECTION_UNSPECIFIED) ProtoDirection.OUTGOING else direction
        val results = mutableListOf<Step>()
        val seen = mutableSetOf(from)
        val frontier = ArrayDeque<Pair<QualifiedName, Int>>().apply { add(from to 0) }
        while (frontier.isNotEmpty()) {
            val (node, depth) = frontier.removeFirst()
            if (depth >= cap) continue
            val edges = edgesAt(node, effectiveDir, edgeTypes)
            for (e in edges) {
                val other =
                    if (effectiveDir == ProtoDirection.INCOMING ||
                        (effectiveDir == ProtoDirection.BOTH && e.target == node)
                    ) {
                        e.source
                    } else {
                        e.target
                    }
                results.add(Step(edge = e, depth = depth + 1))
                if (seen.add(other)) frontier.add(other to depth + 1)
            }
        }
        return results
    }

    private fun edgesAt(
        node: QualifiedName,
        direction: ProtoDirection,
        edgeTypes: Set<ProtoEdgeType>,
    ): List<Edge> {
        val filter: (Edge) -> Boolean = { edgeTypes.isEmpty() || it.type in edgeTypes }
        return when (direction) {
            ProtoDirection.OUTGOING -> outgoing[node]?.filter(filter).orEmpty()
            ProtoDirection.INCOMING -> incoming[node]?.filter(filter).orEmpty()
            ProtoDirection.BOTH ->
                (outgoing[node]?.filter(filter).orEmpty()) +
                    (incoming[node]?.filter(filter).orEmpty())
            ProtoDirection.DIRECTION_UNSPECIFIED, ProtoDirection.UNRECOGNIZED ->
                outgoing[node]?.filter(filter).orEmpty()
        }
    }

    private fun buildOutgoing(model: Model): Map<QualifiedName, List<Edge>> {
        val acc = mutableMapOf<QualifiedName, MutableList<Edge>>()

        fun add(edge: Edge) {
            acc.getOrPut(edge.source) { mutableListOf() }.add(edge)
        }

        for (obj in model.objectByQname().values) {
            when (obj) {
                is DbColumn -> add(Edge(ProtoEdgeType.DEFINES, obj.qname, obj.table))
                is DbTable -> for (col in obj.columns) add(Edge(ProtoEdgeType.DEFINES, col.qname, obj.qname))
                is DbView -> for (col in obj.columns) add(Edge(ProtoEdgeType.DEFINES, col.qname, obj.qname))
                is DbForeignKey -> {
                    val pairs = obj.fromColumns.zip(obj.toColumns)
                    for ((from, to) in pairs) add(Edge(ProtoEdgeType.REFERENCES, from, to))
                }
                is Attribute -> add(Edge(ProtoEdgeType.DEFINES, obj.qname, obj.entity))
                is Entity -> for (attr in obj.attributes) add(Edge(ProtoEdgeType.DEFINES, attr.qname, obj.qname))
                else -> Unit
            }
        }
        // De-dupe the DEFINES edges added from both sides of column ↔ table and attribute ↔ entity.
        for ((source, edges) in acc.toMap()) {
            val distinct = edges.distinct()
            if (distinct.size != edges.size) acc[source] = distinct.toMutableList()
        }

        for (mapping in model.mappings) {
            when (mapping) {
                is Er2DbEntityMapping ->
                    add(Edge(ProtoEdgeType.MAPS_TO, mapping.entity, mappingTargetQname(mapping.target)))
                is Er2DbAttributeMapping ->
                    when (val t = mapping.target) {
                        is AttributeMappingTarget.Column ->
                            add(Edge(ProtoEdgeType.MAPS_TO, mapping.attribute, t.qname))
                        is AttributeMappingTarget.Expression -> Unit
                    }
                is Er2DbRelationMapping ->
                    add(Edge(ProtoEdgeType.MAPS_TO, mapping.relation, mapping.foreignKey))
                is Er2CncRoleMapping ->
                    add(Edge(ProtoEdgeType.MAPS_TO, mapping.entity, mapping.role))
                else -> Unit
            }
        }

        // USES — slot wired; populated when Query.uses tracking lands (DF-T03 follow-up).
        return acc
    }

    private fun mappingTargetQname(target: MappingTarget): QualifiedName =
        when (target) {
            is MappingTarget.Table -> target.qname
            is MappingTarget.View -> target.qname
            is MappingTarget.SqlQuery -> target.qname
        }

    companion object {
        const val MAX_DEPTH_CAP: Int = 10
    }
}
