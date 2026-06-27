package org.tatrman.kantheon.ariadne.export

import org.tatrman.plan.v1.schemaCodeToToken
import org.tatrman.kantheon.ariadne.model.Attribute
import org.tatrman.kantheon.ariadne.model.Cardinality
import org.tatrman.kantheon.ariadne.model.CncSchema
import org.tatrman.kantheon.ariadne.model.DbColumn
import org.tatrman.kantheon.ariadne.model.DbForeignKey
import org.tatrman.kantheon.ariadne.model.DbProcedure
import org.tatrman.kantheon.ariadne.model.DbSchema
import org.tatrman.kantheon.ariadne.model.DbTable
import org.tatrman.kantheon.ariadne.model.DbView
import org.tatrman.kantheon.ariadne.model.Entity
import org.tatrman.kantheon.ariadne.model.Er2CncRoleMapping
import org.tatrman.kantheon.ariadne.model.Er2DbAttributeMapping
import org.tatrman.kantheon.ariadne.model.Er2DbEntityMapping
import org.tatrman.kantheon.ariadne.model.Er2DbRelationMapping
import org.tatrman.kantheon.ariadne.model.ErSchema
import org.tatrman.kantheon.ariadne.model.LocalizedText
import org.tatrman.kantheon.ariadne.model.Mapping
import org.tatrman.kantheon.ariadne.model.MappingTarget
import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.kantheon.ariadne.model.Query
import org.tatrman.kantheon.ariadne.model.Relation
import org.tatrman.kantheon.ariadne.model.Role
import org.tatrman.kantheon.ariadne.model.SearchHints
import org.tatrman.ttr.parser.model.AttributeDef
import org.tatrman.ttr.parser.model.BindingProperty
import org.tatrman.ttr.parser.model.BindingPropertyBareId
import org.tatrman.ttr.parser.model.BindingPropertyBlock
import org.tatrman.ttr.parser.model.ColumnDef
import org.tatrman.ttr.parser.model.DataType
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.Er2CncRoleDef
import org.tatrman.ttr.parser.model.Er2DbAttributeDef
import org.tatrman.ttr.parser.model.Er2DbEntityDef
import org.tatrman.ttr.parser.model.Er2DbRelationDef
import org.tatrman.ttr.parser.model.FkDef
import org.tatrman.ttr.parser.model.LocalizedStringValue
import org.tatrman.ttr.parser.model.ProcedureDef
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.QueryDef
import org.tatrman.ttr.parser.model.Reference
import org.tatrman.ttr.parser.model.RelationDef
import org.tatrman.ttr.parser.model.RoleDef
import org.tatrman.ttr.parser.model.SearchHintsValue
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.parser.model.TableDef
import org.tatrman.ttr.parser.model.TargetObjectValue
import org.tatrman.ttr.parser.model.ViewDef

/**
 * One rendered file in an [ExportBundle]: filename, schema directive info, and
 * the list of definitions to emit in that file.
 */
data class ExportFile(
    val filename: String,
    val schemaCode: String?,
    val namespace: String?,
    val definitions: List<org.tatrman.ttr.parser.model.Definition>,
    val packageName: String? = null,
    val imports: List<String> = emptyList(),
)

/**
 * A model export partitioned into per-schema files, ready for tarball assembly.
 */
data class ExportBundle(
    val modelId: String,
    val modelVersion: String,
    val files: List<ExportFile>,
)

/**
 * Groups `model.mappings` by the qname of the def each one attaches to, so the
 * entity / attribute / relation converters can emit the mapping **inline**
 * (v2.1) instead of standalone `def er2db_*` blocks. `er2cnc_role` mappings have
 * no inline form and are intentionally absent here — they stay standalone.
 */
data class MappingLookup(
    val entityMappings: Map<org.tatrman.plan.v1.QualifiedName, Er2DbEntityMapping>,
    val attributeMappings: Map<org.tatrman.plan.v1.QualifiedName, Er2DbAttributeMapping>,
    val relationMappings: Map<org.tatrman.plan.v1.QualifiedName, Er2DbRelationMapping>,
) {
    companion object {
        val EMPTY = MappingLookup(emptyMap(), emptyMap(), emptyMap())

        fun from(mappings: List<Mapping>): MappingLookup =
            MappingLookup(
                entityMappings = mappings.filterIsInstance<Er2DbEntityMapping>().associateBy { it.entity },
                attributeMappings = mappings.filterIsInstance<Er2DbAttributeMapping>().associateBy { it.attribute },
                relationMappings = mappings.filterIsInstance<Er2DbRelationMapping>().associateBy { it.relation },
            )
    }
}

object ModelToDefinitions {
    private val LOC = SourceLocation.UNKNOWN

    // Factory helpers for the synthesized PropertyValues this exporter emits.
    // The published org.tatrman model carries a `source` on every PropertyValue
    // variant (and `parts` on IdValue); synthesized export values have no real
    // source, so they all use LOC. `parts` mirrors the walker's `path.split(".")`
    // so the export round-trips structurally through a re-parse.
    private fun idVal(ref: Reference) = PropertyValue.IdValue(ref, ref.path.split("."), LOC)

    private fun strVal(raw: String) = PropertyValue.StringValue(raw, LOC)

    private fun listVal(items: List<PropertyValue>) = PropertyValue.ListValue(items, LOC)

    private fun objVal(entries: Map<String, PropertyValue>) = PropertyValue.ObjectValue(entries, LOC)

    /** Stock roles come from BuiltinStockSource; their sourceFile ends with this path. */
    private const val STOCK_ROLES_PATH = "cnc-stock-roles.ttr"

    /**
     * Converts the full [Model] into an [ExportBundle] partitioned **per source file**:
     * one package directory per originating YAML file (package name = file name minus its
     * `NNnn_` code prefix, e.g. `bt09_navsteva` → `navsteva`). Inside each package the
     * content collapses to at most three **directive-less** files (v2.1 / yaml-converter
     * split): `db.ttr` (tables, views, foreign keys, procedures, **and queries**),
     * `er.ttr` (entities **and** relations, with inline `mapping:`), and `cnc.ttr` (roles
     * **and** `er2cnc_role`). Each def derives its schema+namespace from its kind on
     * reload, so these files carry no `schema`/`namespace` directive. er2db mappings are
     * inline (no `map.ttr`).
     *
     * References are emitted fully-qualified, so they resolve via the resolver's exact
     * `byFull` step regardless of imports — no `import` statements are emitted (packages are
     * directory/organisational only for now).
     *
     * Objects with no source file (e.g. hand-built models) fall into a single default
     * (root, package-less) bundle. Stock roles are excluded — re-supplied at import time.
     */
    fun convert(model: Model): ExportBundle {
        data class Tagged(
            val def: org.tatrman.ttr.parser.model.Definition,
            val schema: String,
            val sourceFile: String,
        )

        // v2.1: entity/attribute/relation mappings are attached inline (below) via
        // this lookup; only `er2cnc_role` mappings remain standalone `def` blocks.
        val mappingLookup = MappingLookup.from(model.mappings)

        val tagged = mutableListOf<Tagged>()
        for ((_, schema) in model.schemas) {
            when (schema) {
                is ErSchema -> {
                    schema.entities.values.forEach {
                        tagged += Tagged(entityToEntityDef(it, mappingLookup), "er", it.sourceFile)
                    }
                    // Relations fold into `er.ttr`; the directive-less file lets each def
                    // derive its own schema+namespace by kind (entity→entity, relation→relation).
                    schema.relations.values.forEach {
                        tagged +=
                            Tagged(relationToRelationDef(it, mappingLookup), "er", it.sourceFile)
                    }
                }
                is DbSchema -> {
                    schema.tables.values.forEach { tagged += Tagged(tableToTableDef(it), "db", it.sourceFile) }
                    schema.views.values.forEach { tagged += Tagged(viewToViewDef(it), "db", it.sourceFile) }
                    schema.procedures.values.forEach {
                        tagged +=
                            Tagged(procedureToProcedureDef(it), "db", it.sourceFile)
                    }
                    schema.foreignKeys.values.forEach { tagged += Tagged(foreignKeyToFkDef(it), "db", it.sourceFile) }
                }
                is CncSchema ->
                    schema.roles.values
                        .filter { !it.sourceFile.endsWith(STOCK_ROLES_PATH) }
                        .forEach { tagged += Tagged(roleToRoleDef(it), "cnc", it.sourceFile) }
                else -> Unit
            }
        }
        // er2db_* mappings are now emitted inline on their owning entity/attribute/
        // relation def (see mappingLookup above); only er2cnc_role stays standalone.
        model.mappings.forEach { mapping ->
            if (mapping is Er2CncRoleMapping) {
                tagged += Tagged(er2cncRoleMappingToDef(mapping), "cnc", mapping.sourceFile)
            }
        }
        // Queries live in `db.ttr` (they describe DB-side SQL); they derive the `query`
        // schema from their kind in the directive-less file.
        model.queries.values.forEach { tagged += Tagged(queryToQueryDef(it), "db", it.sourceFile) }

        // One file per (package, bucket): at most `db.ttr` / `er.ttr` / `cnc.ttr`.
        // All defs of a bucket — even from several source files in the same package —
        // merge into the single file, and it carries **no** schema/namespace directive
        // (schemaCode = namespace = null); each def derives its schema+namespace from
        // its kind on reload (kind-derived defaults, ttr-semantics ≥ 0.4.0).
        val files = mutableListOf<ExportFile>()
        tagged.groupBy { packageOf(it.sourceFile) }.forEach { (pkg, pkgItems) ->
            pkgItems.groupBy { it.schema }.forEach { (schema, schemaItems) ->
                files +=
                    ExportFile(
                        filename = "$schema.ttr",
                        schemaCode = null,
                        namespace = null,
                        definitions = schemaItems.map { it.def },
                        packageName = pkg,
                        imports = emptyList(),
                    )
            }
        }

        return ExportBundle(
            modelId = model.descriptor.id,
            modelVersion = model.version.value,
            files = files,
        )
    }

    /** Package name for a source file: base name minus extension and the `NNnn_` code prefix. */
    private fun packageOf(sourceFile: String): String? {
        if (sourceFile.isBlank()) return null
        val base =
            sourceFile
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .substringBeforeLast('.')
        return base.replaceFirst(Regex("^[A-Za-z]{2}\\d{2}_"), "").ifBlank { null }
    }

    fun roleToRoleDef(role: Role): RoleDef =
        RoleDef(
            name = role.qname.name,
            source = LOC,
            description = role.description.takeIf { it.isNotEmpty() },
            tags = role.tags,
            label = role.label.toLocalizedStringValue(),
            search = role.search.toSearchHintsValue(),
        )

    fun entityToEntityDef(
        entity: Entity,
        lookup: MappingLookup = MappingLookup.EMPTY,
    ): EntityDef {
        val entityMapping = lookup.entityMappings[entity.qname]
        return EntityDef(
            name = entity.qname.name,
            source = LOC,
            description = entity.description.takeIf { it.isNotEmpty() },
            tags = entity.tags,
            labelPlural = entity.labelPlural.takeIf { it.isNotEmpty() },
            nameAttribute =
                entity.nameAttribute
                    .takeIf { it.isNotEmpty() }
                    ?.let { Reference(it) },
            codeAttribute =
                entity.codeAttribute
                    .takeIf { it.isNotEmpty() }
                    ?.let { Reference(it) },
            aliases = entity.aliases,
            // v2.1: every attribute carries its own inline `mapping:` (short bare-id
            // for a plain column, block form for an expression). The entity-level
            // block holds only `target:` — column mappings are NOT duplicated into a
            // `columns:` map.
            attributes =
                entity.attributes.map {
                    attributeToAttributeDef(it, lookup)
                },
            roles = emptyList(),
            displayLabel = entity.displayLabel.toLocalizedStringValue(),
            search = entity.search.toSearchHintsValue(),
            binding = entityMapping?.let { entityLevelMapping(it) },
        )
    }

    /**
     * Build the entity-level `mapping: { target: … }` block. Column mappings are
     * emitted per-attribute (see [attributeToAttributeDef]), so the block carries no
     * `columns:` map.
     */
    private fun entityLevelMapping(entityMapping: Er2DbEntityMapping): BindingPropertyBlock =
        BindingPropertyBlock(
            target = TargetObjectValue(obj = mappingTargetToPropertyValue(entityMapping.target), source = LOC),
            source = LOC,
        )

    fun tableToTableDef(table: DbTable): TableDef =
        TableDef(
            name = table.qname.name,
            source = LOC,
            description = table.description.takeIf { it.isNotEmpty() },
            tags = table.tags,
            primaryKey = table.primaryKey,
            columns = table.columns.map { columnToColumnDef(it) },
            indices = emptyList(),
            constraints = emptyList(),
        )

    fun viewToViewDef(view: DbView): ViewDef =
        ViewDef(
            name = view.qname.name,
            source = LOC,
            description = view.description.takeIf { it.isNotEmpty() },
            tags = view.tags,
            columns = view.columns.map { columnToColumnDef(it) },
            definitionSql = view.definitionSql.takeIf { it.isNotEmpty() },
        )

    fun procedureToProcedureDef(procedure: DbProcedure): ProcedureDef =
        ProcedureDef(
            name = procedure.qname.name,
            source = LOC,
            description = procedure.description.takeIf { it.isNotEmpty() },
            tags = procedure.tags,
            parameters =
                procedure.parameters.map { param ->
                    objVal(
                        entries =
                            linkedMapOf(
                                "name" to idVal(Reference(param.name)),
                                "type" to idVal(Reference(param.dataType)),
                                "direction" to idVal(Reference(param.direction.name)),
                            ),
                    )
                },
            resultColumns = procedure.resultColumns.map { columnToColumnDef(it) },
        )

    fun foreignKeyToFkDef(fk: DbForeignKey): FkDef =
        FkDef(
            name = fk.qname.name,
            source = LOC,
            description = fk.description.takeIf { it.isNotEmpty() },
            tags = fk.tags,
            from =
                listVal(
                    fk.fromColumns.map { idVal(Reference(qnameToPath(it))) },
                ),
            to =
                listVal(
                    fk.toColumns.map { idVal(Reference(qnameToPath(it))) },
                ),
        )

    fun relationToRelationDef(
        relation: Relation,
        lookup: MappingLookup = MappingLookup.EMPTY,
    ): RelationDef =
        RelationDef(
            name = relation.qname.name,
            source = LOC,
            description = relation.description.takeIf { it.isNotEmpty() },
            tags = relation.tags,
            from = idVal(Reference(qnameToPath(relation.fromEntity))),
            to = idVal(Reference(qnameToPath(relation.toEntity))),
            cardinality = cardinalityToPropertyValue(relation.cardinality),
            join =
                relation.joinPairs.map { pair ->
                    objVal(
                        entries =
                            mapOf(
                                "from" to
                                    idVal(
                                        Reference(qnameToPath(pair.fromAttr)),
                                    ),
                                "to" to
                                    idVal(
                                        Reference(qnameToPath(pair.toAttr)),
                                    ),
                            ),
                    )
                },
            // v2.1 inline FK mapping: short bare-id form `mapping: <fkRef>`.
            binding =
                lookup.relationMappings[relation.qname]?.let {
                    BindingPropertyBareId(Reference(qnameToPath(it.foreignKey)), LOC)
                },
        )

    fun er2dbEntityMappingToDef(mapping: Er2DbEntityMapping): Er2DbEntityDef =
        Er2DbEntityDef(
            name = mapping.qname.name,
            source = LOC,
            description = mapping.description.takeIf { it.isNotEmpty() },
            tags = mapping.tags,
            entity = Reference(qnameToPath(mapping.entity)),
            target = TargetObjectValue(obj = mappingTargetToPropertyValue(mapping.target), source = LOC),
        )

    fun er2dbAttributeMappingToDef(mapping: Er2DbAttributeMapping): Er2DbAttributeDef =
        Er2DbAttributeDef(
            name = mapping.qname.name,
            source = LOC,
            description = mapping.description.takeIf { it.isNotEmpty() },
            tags = mapping.tags,
            attribute = Reference(qnameToPath(mapping.attribute)),
            target = TargetObjectValue(obj = attributeMappingTargetToPropertyValue(mapping.target), source = LOC),
        )

    fun er2dbRelationMappingToDef(mapping: Er2DbRelationMapping): Er2DbRelationDef =
        Er2DbRelationDef(
            name = mapping.qname.name,
            source = LOC,
            description = mapping.description.takeIf { it.isNotEmpty() },
            tags = mapping.tags,
            relation = Reference(qnameToPath(mapping.relation)),
            fk = Reference(qnameToPath(mapping.foreignKey)),
        )

    fun er2cncRoleMappingToDef(mapping: Er2CncRoleMapping): Er2CncRoleDef =
        Er2CncRoleDef(
            name = mapping.qname.name,
            source = LOC,
            description = mapping.description.takeIf { it.isNotEmpty() },
            tags = mapping.tags,
            entity = Reference(qnameToPath(mapping.entity)),
            role = Reference(qnameToPath(mapping.role)),
        )

    fun queryToQueryDef(query: Query): QueryDef =
        QueryDef(
            name = query.qname.name,
            source = LOC,
            description = query.description.takeIf { it.isNotEmpty() },
            tags = query.tags,
            language = query.sourceLanguage.takeIf { it.isNotEmpty() },
            parameters =
                query.parameters.map { p ->
                    objVal(
                        entries =
                            buildMap {
                                put("name", idVal(Reference(p.name)))
                                put("type", idVal(Reference(p.type)))
                                if (p.label.isNotEmpty()) put("label", strVal(p.label))
                            },
                    )
                },
            sourceText = query.sourceText.takeIf { it.isNotEmpty() },
            search = query.search.toSearchHintsValue(),
        )

    private fun attributeToAttributeDef(
        attr: Attribute,
        lookup: MappingLookup = MappingLookup.EMPTY,
    ): AttributeDef =
        AttributeDef(
            name = attr.qname.name.substringAfterLast("."),
            source = LOC,
            description = attr.description.takeIf { it.isNotEmpty() },
            tags = attr.tags,
            type = DataType(attr.type),
            isKey = attr.isKey,
            optional = attr.nullable,
            displayLabel = attr.displayLabel.toLocalizedStringValue(),
            valueLabels = attr.valueLabels.mapValues { (_, v) -> v.toLocalizedStringValue() },
            search = attr.search.toSearchHintsValue(),
            // Per-attribute inline mapping: short bare-id for a plain column, block
            // form for an expression target. Emitted whether or not the owning entity
            // has its own entity-level `mapping: { target: … }` block.
            binding =
                lookup.attributeMappings[attr.qname]?.let { attributeLevelMapping(it.target) },
        )

    private fun attributeLevelMapping(
        target: org.tatrman.kantheon.ariadne.model.AttributeMappingTarget,
    ): BindingProperty =
        when (target) {
            is org.tatrman.kantheon.ariadne.model.AttributeMappingTarget.Column ->
                BindingPropertyBareId(Reference(target.qname.name.substringAfterLast(".")), LOC)
            is org.tatrman.kantheon.ariadne.model.AttributeMappingTarget.Expression ->
                BindingPropertyBlock(
                    target = TargetObjectValue(obj = attributeMappingTargetToPropertyValue(target), source = LOC),
                    source = LOC,
                )
        }

    private fun columnToColumnDef(col: DbColumn): ColumnDef =
        ColumnDef(
            name = col.qname.name.substringAfterLast("."),
            source = LOC,
            description = col.description.takeIf { it.isNotEmpty() },
            tags = col.tags,
            type = normalizeColumnType(col.dataType),
            optional = col.nullable,
            isKey = col.isPrimaryKey,
            indexed = false,
            search = col.search.toSearchHintsValue(),
        )

    private fun normalizeColumnType(raw: String): DataType {
        val base = raw.substringBefore("(").trim().lowercase()
        val canonical =
            when (base) {
                "varchar", "nvarchar", "char", "nchar", "string" -> "text"
                "integer", "smallint", "tinyint" -> "int"
                "bigint", "long" -> "bigint"
                "bool", "boolean", "bit" -> "bool"
                "datetime2", "timestamp" -> "datetime"
                "numeric" -> "decimal"
                "double", "real" -> "float"
                "uuid", "guid", "uniqueidentifier" -> "text"
                else -> base
            }
        return DataType(canonical)
    }

    private fun qnameToPath(qn: org.tatrman.plan.v1.QualifiedName): String {
        // Render with the canonical lowercase schema token. For UNSPECIFIED (e.g. `query`,
        // `map` — schemas not present in the SchemaCode enum) fall back to the namespace
        // as the de-facto schema prefix so the resulting 3-part path round-trips through
        // Reference.toQname (which restores defaultSchema=parts[0] for 3+-part inputs).
        // If a package is set, prepend it to produce a 4-part path.
        val token = schemaCodeToToken(qn.schemaCode)
        val schemaToken = if (token.isNotEmpty()) token else qn.namespace
        val packageSegment = if (qn.`package`.isNotEmpty()) "${qn.`package`}." else ""
        return "$packageSegment$schemaToken.${qn.namespace}.${qn.name}"
    }

    private fun rangeString(
        min: Int,
        max: Int,
    ): String =
        when {
            max == -1 -> "$min..*"
            min == max -> "$min"
            else -> "$min..$max"
        }

    private fun cardinalityToPropertyValue(c: Cardinality): PropertyValue.ObjectValue =
        objVal(
            entries =
                mapOf(
                    "from" to strVal(rangeString(c.fromMin, c.fromMax)),
                    "to" to strVal(rangeString(c.toMin, c.toMax)),
                ),
        )

    private fun mappingTargetToPropertyValue(target: MappingTarget): PropertyValue.ObjectValue =
        when (target) {
            is MappingTarget.Table ->
                objVal(
                    entries =
                        mapOf(
                            "table" to
                                idVal(
                                    Reference(qnameToPath(target.qname)),
                                ),
                        ),
                )
            is MappingTarget.View ->
                objVal(
                    entries =
                        mapOf(
                            "view" to
                                idVal(
                                    Reference(qnameToPath(target.qname)),
                                ),
                        ),
                )
            is MappingTarget.SqlQuery ->
                objVal(
                    entries =
                        mapOf(
                            "sqlQuery" to
                                idVal(
                                    Reference(qnameToPath(target.qname)),
                                ),
                        ),
                )
        }

    private fun attributeMappingTargetToPropertyValue(
        target: org.tatrman.kantheon.ariadne.model.AttributeMappingTarget,
    ): PropertyValue.ObjectValue =
        when (target) {
            is org.tatrman.kantheon.ariadne.model.AttributeMappingTarget.Column ->
                objVal(
                    entries =
                        mapOf(
                            "column" to
                                idVal(
                                    Reference(qnameToPath(target.qname)),
                                ),
                        ),
                )
            is org.tatrman.kantheon.ariadne.model.AttributeMappingTarget.Expression ->
                objVal(
                    entries =
                        mapOf(
                            "expression" to
                                strVal(target.raw),
                        ),
                )
        }

    private fun LocalizedText.toLocalizedStringValue(): LocalizedStringValue =
        LocalizedStringValue(byLanguage = byLanguage)

    private fun SearchHints.toSearchHintsValue(): SearchHintsValue =
        SearchHintsValue(
            searchable = searchable,
            fuzzy = fuzzy,
            keywords = keywords.toLocalizedStringListValue(),
            patterns = patterns,
            descriptions = descriptions.toLocalizedStringListValue(),
            examples = examples,
            aliases = aliases,
        )

    private fun org.tatrman.kantheon.ariadne.model.LocalizedTextList.toLocalizedStringListValue():
        org.tatrman.ttr.parser.model.LocalizedStringListValue =
        org.tatrman.ttr.parser.model.LocalizedStringListValue(
            byLanguage = byLanguage,
        )
}
