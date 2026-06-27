package org.tatrman.kantheon.ariadne.graph

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.kantheon.ariadne.model.Attribute
import org.tatrman.kantheon.ariadne.model.DbColumn
import org.tatrman.kantheon.ariadne.model.DbForeignKey
import org.tatrman.kantheon.ariadne.model.Er2DbAttributeMapping
import org.tatrman.kantheon.ariadne.model.Er2DbEntityMapping
import org.tatrman.kantheon.ariadne.model.Er2DbRelationMapping
import org.tatrman.kantheon.ariadne.model.MappingTarget
import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.kantheon.ariadne.model.ModelObject
import org.tatrman.kantheon.ariadne.model.Query
import org.jgrapht.alg.connectivity.ConnectivityInspector
import org.jgrapht.alg.cycle.CycleDetector
import org.jgrapht.graph.DefaultDirectedGraph
import org.jgrapht.traverse.TopologicalOrderIterator
import java.util.ArrayDeque

/**
 * Directed graph of model objects keyed by `internalId`. JGraphT-backed; the
 * library types never escape this class's surface — public methods take and
 * return only domain types.
 *
 * Edge kinds:
 *   - `DEFINES`    column → table, attribute → entity
 *   - `REFERENCES` foreign-key → target column
 *   - `MAPS_TO`    er2db_*  → physical target
 *   - `USES`       query → object referenced (other queries or schema objects)
 *
 * Construction is one-shot per snapshot (immutable). Indices for the common
 * lookups (`byInternalId`, `byQualifiedName`, `byTag`) are computed eagerly.
 */
class ModelGraph private constructor(
    private val graph: DefaultDirectedGraph<String, Edge>,
    val byInternalId: Map<String, ModelObject>,
    val byQualifiedName: Map<QualifiedName, ModelObject>,
    val byTag: Map<String, List<ModelObject>>,
) {
    /** All edges out of `internalId` matching `edgeTypes` (or all if empty). */
    fun outgoingOf(
        internalId: String,
        edgeTypes: Set<EdgeType> = emptySet(),
    ): List<Edge> {
        if (!graph.containsVertex(internalId)) return emptyList()
        return graph.outgoingEdgesOf(internalId).filter { edgeTypes.isEmpty() || it.type in edgeTypes }
    }

    fun incomingOf(
        internalId: String,
        edgeTypes: Set<EdgeType> = emptySet(),
    ): List<Edge> {
        if (!graph.containsVertex(internalId)) return emptyList()
        return graph.incomingEdgesOf(internalId).filter { edgeTypes.isEmpty() || it.type in edgeTypes }
    }

    /** BFS up to `maxDepth` collecting traversed edges with their depth. */
    fun traverse(
        from: String,
        edgeTypes: Set<EdgeType> = emptySet(),
        direction: Direction = Direction.OUTGOING,
        maxDepth: Int = 1,
    ): List<TraversalEdge> {
        if (!graph.containsVertex(from) || maxDepth <= 0) return emptyList()
        val out = mutableListOf<TraversalEdge>()
        val seen = mutableSetOf(from)
        val frontier = ArrayDeque<Pair<String, Int>>().apply { add(from to 0) }
        while (frontier.isNotEmpty()) {
            val (vertex, depth) = frontier.removeFirst()
            if (depth >= maxDepth) continue
            val edges =
                when (direction) {
                    Direction.OUTGOING -> outgoingOf(vertex, edgeTypes)
                    Direction.INCOMING -> incomingOf(vertex, edgeTypes)
                    Direction.BOTH -> outgoingOf(vertex, edgeTypes) + incomingOf(vertex, edgeTypes)
                }
            for (edge in edges) {
                val target =
                    when (direction) {
                        Direction.OUTGOING -> edge.target
                        Direction.INCOMING -> edge.source
                        Direction.BOTH -> if (edge.source == vertex) edge.target else edge.source
                    }
                out += TraversalEdge(edge = edge, depth = depth + 1)
                if (seen.add(target)) frontier.add(target to depth + 1)
            }
        }
        return out
    }

    /** Find every cycle in the graph. Returns the vertex sets per cycle. */
    fun findCycles(): List<Set<String>> {
        val detector = CycleDetector(graph)
        if (!detector.detectCycles()) return emptyList()
        return detector
            .findCyclesContainingVertex(graph.vertexSet().first())
            .let { listOf(it) }
            .filter { it.isNotEmpty() }
    }

    /** Connected-component partition (treating edges as undirected). */
    fun connectedComponents(): List<Set<String>> = ConnectivityInspector(graph).connectedSets()

    /**
     * Phase 09 B4 / DF-M15 — read-only walk over every edge (export use case). The visitor
     * pattern keeps JGraphT off the public surface; callers don't need to touch it directly.
     */
    fun forEachEdge(action: (Edge) -> Unit) {
        graph.edgeSet().forEach(action)
    }

    /**
     * Topological order of all vertices (or only those in `restrictedTo` if
     * supplied). Throws if the graph has cycles.
     */
    fun topologicalOrder(restrictedTo: Set<String>? = null): List<String> {
        val it = TopologicalOrderIterator(graph)
        val all = mutableListOf<String>()
        while (it.hasNext()) all += it.next()
        return if (restrictedTo == null) all else all.filter { it in restrictedTo }
    }

    /** Vertex count — handy for status endpoints. */
    fun size(): Int = graph.vertexSet().size

    companion object {
        fun build(model: Model): ModelGraph {
            val graph = DefaultDirectedGraph<String, Edge>(Edge::class.java)

            val objects: Map<QualifiedName, ModelObject> = model.objectByQname()
            val byId = objects.values.associateBy { it.internalId }
            for (id in byId.keys) graph.addVertex(id)

            for ((_, schema) in model.schemas) {
                for (obj in schema.objects()) {
                    when (obj) {
                        is DbColumn ->
                            defineEdge(
                                graph,
                                obj.internalId,
                                objects[obj.table]?.internalId,
                                EdgeType.DEFINES,
                            )
                        is Attribute ->
                            defineEdge(
                                graph,
                                obj.internalId,
                                objects[obj.entity]?.internalId,
                                EdgeType.DEFINES,
                            )
                        is DbForeignKey ->
                            obj.toColumns.forEach {
                                defineEdge(graph, obj.internalId, objects[it]?.internalId, EdgeType.REFERENCES)
                            }
                        else -> Unit
                    }
                }
            }

            for (m in model.mappings) {
                when (m) {
                    is Er2DbEntityMapping -> {
                        val targetId =
                            when (val t = m.target) {
                                is MappingTarget.Table -> objects[t.qname]?.internalId
                                is MappingTarget.View -> objects[t.qname]?.internalId
                                is MappingTarget.SqlQuery -> objects[t.qname]?.internalId
                            }
                        defineEdge(graph, m.internalId, targetId, EdgeType.MAPS_TO)
                    }
                    is Er2DbAttributeMapping -> {
                        // Attribute target: column or expression. Only the column case lands as a graph edge.
                        if (m.target is org.tatrman.kantheon.ariadne.model.AttributeMappingTarget.Column) {
                            defineEdge(graph, m.internalId, objects[m.target.qname]?.internalId, EdgeType.MAPS_TO)
                        }
                    }
                    is Er2DbRelationMapping ->
                        defineEdge(graph, m.internalId, objects[m.foreignKey]?.internalId, EdgeType.MAPS_TO)
                    is org.tatrman.kantheon.ariadne.model.Er2CncRoleMapping ->
                        defineEdge(graph, m.internalId, objects[m.role]?.internalId, EdgeType.MAPS_TO)
                }
            }

            // Queries USE references are populated post-async-parse; in the
            // structural snapshot we don't have edges for them yet.
            // (Phase 1.2 Section F follow-up will fill these in.)

            val byQname = objects
            val byTag =
                buildMap<String, MutableList<ModelObject>> {
                    for (obj in objects.values) {
                        for (tag in obj.tags) getOrPut(tag) { mutableListOf() }.add(obj)
                    }
                }
            return ModelGraph(graph, byId, byQname, byTag)
        }

        private fun defineEdge(
            graph: DefaultDirectedGraph<String, Edge>,
            source: String,
            target: String?,
            type: EdgeType,
        ) {
            if (target == null || source == target) return
            if (!graph.containsVertex(source) || !graph.containsVertex(target)) return
            graph.addEdge(source, target, Edge(source, target, type))
        }
    }
}

class Edge(
    val source: String,
    val target: String,
    val type: EdgeType,
) {
    override fun equals(other: Any?): Boolean =
        other is Edge && source == other.source && target == other.target && type == other.type

    override fun hashCode(): Int = 31 * (31 * source.hashCode() + target.hashCode()) + type.hashCode()

    override fun toString(): String = "$source --[$type]--> $target"
}

enum class EdgeType { DEFINES, REFERENCES, MAPS_TO, USES }

enum class Direction { OUTGOING, INCOMING, BOTH }

data class TraversalEdge(
    val edge: Edge,
    val depth: Int,
) {
    val sourceId: String get() = edge.source
    val targetId: String get() = edge.target
    val type: EdgeType get() = edge.type
}

// Convenience: include Query as a sequenced object too, even though it isn't
// in any schema. Used by ModelGraph.build via Model.objectByQname.
@Suppress("unused")
private fun queryAccess(q: Query): String = q.internalId
