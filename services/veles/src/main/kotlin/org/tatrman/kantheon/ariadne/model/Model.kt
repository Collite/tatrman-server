package org.tatrman.kantheon.ariadne.model

import org.tatrman.plan.v1.QualifiedName
import java.time.Instant

/**
 * Top-level immutable snapshot of the metadata model.
 *
 * Constructed by the [org.tatrman.kantheon.ariadne.reconcile.ModelReconciler] from one or
 * more [org.tatrman.kantheon.ariadne.source.SourceSnapshot]s and held by
 * [org.tatrman.kantheon.ariadne.registry.MetadataRegistry] under an `AtomicReference`.
 * Consumers of a snapshot must not mutate it; mutations land via a fresh
 * snapshot swap.
 */
data class Model(
    val descriptor: ModelDescriptor,
    val version: ModelVersion,
    val schemas: Map<String, SchemaContents>,
    val mappings: List<Mapping>,
    val queries: Map<QualifiedName, Query>,
    /**
     * v2.2 — drill maps loaded from `def drill_map` blocks. Keyed by qname for
     * deterministic ordering; consumers iterating to populate proto bundles
     * typically just call `drillMaps.values`.
     */
    val drillMaps: Map<QualifiedName, DrillMap> = emptyMap(),
    /**
     * Golem P4 S4.2 — subject areas loaded from `def area` blocks in `.ttrm`
     * files. Keyed by the bare area name (`accounting`). Surfaced by the
     * `ResolveArea` RPC so a Golem Shem can resolve its `areas: [...]` list to
     * the concrete package set it must pull from Ariadne.
     */
    val areas: Map<String, AreaRecord> = emptyMap(),
) {
    /** Resolve a subject area by its bare name, or null if unknown. */
    fun areaByName(name: String): AreaRecord? = areas[name]

    /** Every object in the model, indexed by its qualified name. */
    fun objectByQname(): Map<QualifiedName, ModelObject> {
        val out = mutableMapOf<QualifiedName, ModelObject>()
        for ((_, schema) in schemas) {
            schema.objects().forEach { obj -> out[obj.qname] = obj }
        }
        for (m in mappings) out[m.qname] = m
        for (q in queries.values) out[q.qname] = q
        for (d in drillMaps.values) out[d.qname] = d
        return out
    }
}

/**
 * Golem P4 S4.2 — a `def area` block loaded from a `.ttrm` area file. Carries the
 * area's display description, tags, and the package set the area spans. Referenced
 * package names are kept verbatim (not validated against the model here — the
 * `GetModel` call validates packages later).
 */
data class AreaRecord(
    val name: String,
    val description: String,
    val tags: List<String>,
    val packages: List<String>,
)

/** Descriptive identity of a model bundle. */
data class ModelDescriptor(
    val id: String,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
)

/** Snapshot version stamp — opaque token, monotonic across swaps. */
data class ModelVersion(
    val value: String,
    val swappedAt: Instant,
)

/** Common surface for every queryable object in the model. */
sealed interface ModelObject {
    val internalId: String
    val qname: QualifiedName
    val kind: String
    val description: String
    val tags: List<String>
    val sourceFile: String
    val binding: Binding
}

/** Bound state of a model object — Round 4's first-class "synthetic" concept. */
sealed interface Binding {
    val reason: String

    data object BoundReal : Binding {
        override val reason: String = ""
    }

    data class BoundSynthetic(
        override val reason: String,
    ) : Binding

    data class Unbound(
        override val reason: String,
    ) : Binding
}

// ----- Schema layer -----

sealed interface SchemaContents {
    val schemaCode: String

    fun objects(): Sequence<ModelObject>
}

data class DbSchema(
    val namespace: String = "dbo",
    val tables: Map<QualifiedName, DbTable> = emptyMap(),
    val views: Map<QualifiedName, DbView> = emptyMap(),
    val procedures: Map<QualifiedName, DbProcedure> = emptyMap(),
    val foreignKeys: Map<QualifiedName, DbForeignKey> = emptyMap(),
) : SchemaContents {
    override val schemaCode: String = "db"

    override fun objects(): Sequence<ModelObject> =
        sequence {
            yieldAll(tables.values)
            tables.values.forEach { yieldAll(it.columns) }
            yieldAll(views.values)
            views.values.forEach { yieldAll(it.columns) }
            yieldAll(procedures.values)
            yieldAll(foreignKeys.values)
        }
}

data class ErSchema(
    val entities: Map<QualifiedName, Entity> = emptyMap(),
    val relations: Map<QualifiedName, Relation> = emptyMap(),
) : SchemaContents {
    override val schemaCode: String = "er"

    override fun objects(): Sequence<ModelObject> =
        sequence {
            yieldAll(entities.values)
            entities.values.forEach { yieldAll(it.attributes) }
            yieldAll(relations.values)
        }
}

/**
 * Phase 2.2 — conceptual schema (`cnc`). Currently holds [Role] objects;
 * future work (v1.5+) extends with traits / taxonomies.
 */
data class CncSchema(
    val roles: Map<QualifiedName, Role> = emptyMap(),
) : SchemaContents {
    override val schemaCode: String = "cnc"

    override fun objects(): Sequence<ModelObject> = sequence { yieldAll(roles.values) }
}

// ----- DB types -----

data class DbTable(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val columns: List<DbColumn> = emptyList(),
    val primaryKey: List<String> = emptyList(),
) : ModelObject {
    override val kind: String = "table"
}

data class DbView(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val columns: List<DbColumn> = emptyList(),
    val definitionSql: String = "",
) : ModelObject {
    override val kind: String = "view"
}

data class DbColumn(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val table: QualifiedName,
    val dataType: String,
    val nullable: Boolean = true,
    val isPrimaryKey: Boolean = false,
    val isForeignKey: Boolean = false,
    val search: SearchHints = SearchHints.EMPTY,
) : ModelObject {
    override val kind: String = "column"
}

data class DbProcedure(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val parameters: List<DbProcedureParameter> = emptyList(),
    val resultColumns: List<DbColumn> = emptyList(),
) : ModelObject {
    override val kind: String = "procedure"
}

data class DbProcedureParameter(
    val name: String,
    val dataType: String,
    val direction: ParameterDirection,
)

enum class ParameterDirection { IN, OUT, INOUT }

data class DbForeignKey(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val fromColumns: List<QualifiedName>,
    val toColumns: List<QualifiedName>,
) : ModelObject {
    override val kind: String = "foreign_key"
}

// ----- ER types -----

data class Entity(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val labelPlural: String = "",
    val nameAttribute: String = "",
    val codeAttribute: String = "",
    val aliases: List<String> = emptyList(),
    val attributes: List<Attribute> = emptyList(),
    /** Phase 2.2 (G5) — localised entity name. Empty when absent. */
    val displayLabel: LocalizedText = LocalizedText.EMPTY,
    /** Search feature — search hints aggregated from the `search { ... }` block. */
    val search: SearchHints = SearchHints.EMPTY,
) : ModelObject {
    override val kind: String = "entity"
}

data class Attribute(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val entity: QualifiedName,
    val type: String,
    val isKey: Boolean = false,
    val nullable: Boolean = true,
    /** Phase 2.2 (G5) — localised attribute name. Empty when absent. */
    val displayLabel: LocalizedText = LocalizedText.EMPTY,
    /** Phase 2.2 (G4) — code → localised label, e.g. "1" → cs:"Aktivní". */
    val valueLabels: Map<String, LocalizedText> = emptyMap(),
    /** Search feature — search hints aggregated from the `search { ... }` block. */
    val search: SearchHints = SearchHints.EMPTY,
) : ModelObject {
    override val kind: String = "attribute"
}

/**
 * Phase 2.2 — localised text carrier in the metadata model layer (no proto
 * dependency in this package). Converted to/from the proto `LocalizedString`
 * by the gRPC layer.
 */
data class LocalizedText(
    val byLanguage: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = byLanguage.isEmpty()

    companion object {
        val EMPTY: LocalizedText = LocalizedText(emptyMap())
    }
}

/**
 * Search feature — internal model carrier for `search { ... }` content.
 * Mirrors the parser-side `SearchHintsValue` and the proto `SearchHints`
 * but lives in this layer with no proto dependency. Converted by the source
 * loader (in) and the gRPC layer (out).
 */
data class LocalizedTextList(
    val byLanguage: Map<String, List<String>> = emptyMap(),
) {
    val isEmpty: Boolean get() = byLanguage.isEmpty()

    companion object {
        val EMPTY: LocalizedTextList = LocalizedTextList(emptyMap())
    }
}

data class SearchHints(
    val searchable: Boolean = false,
    val fuzzy: Boolean = false,
    val keywords: LocalizedTextList = LocalizedTextList.EMPTY,
    val patterns: List<String> = emptyList(),
    val descriptions: LocalizedTextList = LocalizedTextList.EMPTY,
    val examples: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() =
            !searchable &&
                !fuzzy &&
                keywords.isEmpty &&
                patterns.isEmpty() &&
                descriptions.isEmpty &&
                examples.isEmpty() &&
                aliases.isEmpty()

    companion object {
        val EMPTY: SearchHints = SearchHints()
    }
}

data class Relation(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val fromEntity: QualifiedName,
    val toEntity: QualifiedName,
    val cardinality: Cardinality,
    val joinPairs: List<AttributeJoinPair> = emptyList(),
) : ModelObject {
    override val kind: String = "relation"
}

data class Cardinality(
    val fromMin: Int,
    val fromMax: Int,
    val toMin: Int,
    val toMax: Int,
)

data class AttributeJoinPair(
    val fromAttr: QualifiedName,
    val toAttr: QualifiedName,
)

// ----- Mapping types -----

sealed interface Mapping : ModelObject {
    /**
     * v2.1 — distinguishes an explicit `def er2db_*` declaration from a symbol
     * synthesized from an inline `mapping:` property on a host
     * entity / attribute / relation. Used by the duplicate-mapping validator;
     * lookups (foreign-key resolution etc.) treat both the same.
     */
    val mappingSource: MappingSource
}

/** v2.1 — origin of an `Er2Db*Mapping` entry. */
sealed interface MappingSource {
    /** Materialised from a `def er2db_*` declaration. */
    data object Explicit : MappingSource

    /**
     * Synthesised from an inline `mapping:` block on an entity / attribute /
     * relation. [hostKind] is the host's def kind: `"entity"`, `"attribute"`,
     * or `"relation"`.
     */
    data class Inline(
        val hostKind: String,
    ) : MappingSource
}

data class Er2DbEntityMapping(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val mappingSource: MappingSource = MappingSource.Explicit,
    val entity: QualifiedName,
    val target: MappingTarget,
) : Mapping {
    override val kind: String = "er2db_entity_mapping"
}

data class Er2DbAttributeMapping(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val mappingSource: MappingSource = MappingSource.Explicit,
    val attribute: QualifiedName,
    val target: AttributeMappingTarget,
) : Mapping {
    override val kind: String = "er2db_attribute_mapping"
}

data class Er2DbRelationMapping(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val mappingSource: MappingSource = MappingSource.Explicit,
    val relation: QualifiedName,
    val foreignKey: QualifiedName,
) : Mapping {
    override val kind: String = "er2db_relation_mapping"
}

/** Phase 2.2 — conceptual role assigned to an ER entity. */
data class Role(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val label: LocalizedText = LocalizedText.EMPTY,
    /** Search feature — search hints aggregated from the `search { ... }` block. */
    val search: SearchHints = SearchHints.EMPTY,
) : ModelObject {
    override val kind: String = "role"
}

/** Phase 2.2 — `er.entity.X plays cnc.role.Y` mapping. */
data class Er2CncRoleMapping(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    override val mappingSource: MappingSource = MappingSource.Explicit,
    val entity: QualifiedName,
    val role: QualifiedName,
) : Mapping {
    override val kind: String = "er2cnc_role_mapping"
}

sealed interface MappingTarget {
    data class Table(
        val qname: QualifiedName,
    ) : MappingTarget

    data class View(
        val qname: QualifiedName,
    ) : MappingTarget

    data class SqlQuery(
        val qname: QualifiedName,
    ) : MappingTarget
}

sealed interface AttributeMappingTarget {
    data class Column(
        val qname: QualifiedName,
    ) : AttributeMappingTarget

    /** Free-form expression source — preserved for round-trip but unparsed here. */
    data class Expression(
        val raw: String,
    ) : AttributeMappingTarget
}

// ----- Query type -----

data class Query(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    val sourceLanguage: String,
    val sourceText: String,
    val parameters: List<QueryParameterDef> = emptyList(),
    /**
     * Mutable async-parse status. The query-parse worker (Phase 1.2 Section F
     * follow-up) updates this in place; readers are expected to capture a
     * single read at the start of a request and use that consistently.
     */
    val parseStatus: ParseStatus = ParseStatus.ParsePending,
    /** Search feature — search hints aggregated from the `search { ... }` block. */
    val search: SearchHints = SearchHints.EMPTY,
) : ModelObject {
    override val kind: String = "query"
}

data class QueryParameterDef(
    val name: String,
    val type: String,
    val label: String = "",
)

sealed interface ParseStatus {
    data object ParsePending : ParseStatus

    data class ParseSuccess(
        val canonicalFormProtoBytes: ByteArray,
    ) : ParseStatus {
        override fun equals(other: Any?): Boolean =
            other is ParseSuccess && canonicalFormProtoBytes.contentEquals(other.canonicalFormProtoBytes)

        override fun hashCode(): Int = canonicalFormProtoBytes.contentHashCode()
    }

    data class ParseFailure(
        val message: String,
        val location: String = "",
    ) : ParseStatus
}

// ----- v2.2 — drill maps -----

/**
 * v2.2 — `def drill_map { from, to, args, display?, override? }`. Lives in the
 * `query.drill.*` namespace (contracts §3). Always carries a non-null `display`
 * after load: the AST→model mapper supplies a default of "Detail" / "Detail
 * <to.description>" when the source TTR omitted one (OQ-03.A).
 */
data class DrillMap(
    override val internalId: String,
    override val qname: QualifiedName,
    override val description: String = "",
    override val tags: List<String> = emptyList(),
    override val sourceFile: String = "",
    override val binding: Binding = Binding.BoundReal,
    /** Reference to a `def query` pattern. Resolved by the loader; may be Unbound. */
    val fromPattern: QualifiedName,
    /** Reference to a `def query` pattern. Resolved by the loader; may be Unbound. */
    val toPattern: QualifiedName,
    /** target_param_name -> source column name or quoted literal. */
    val argMapping: Map<String, String> = emptyMap(),
    /** True for explicit `def drill_map`; false for auto-derived (future). */
    val explicit: Boolean = true,
    /** If explicit && true, suppresses auto-derived drills with the same target. */
    val overrideAuto: Boolean = false,
    val display: LocalizedText = LocalizedText.EMPTY,
) : ModelObject {
    override val kind: String = "drill_map"
}
