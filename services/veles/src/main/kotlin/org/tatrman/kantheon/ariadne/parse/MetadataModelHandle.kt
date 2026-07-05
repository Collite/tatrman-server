package org.tatrman.kantheon.ariadne.parse

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.kantheon.ariadne.grpc.toDomain
import org.tatrman.kantheon.ariadne.grpc.toProto
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.model.Model
import shared.translator.framework.ModelColumn
import shared.translator.framework.ModelForeignKey
import shared.translator.framework.ModelHandle
import shared.translator.framework.ModelTable
import shared.translator.framework.SurfaceType

import org.tatrman.plan.v1.SchemaCode
import shared.translator.framework.EntityMapping
import shared.translator.framework.ModelAttribute
import shared.translator.framework.ModelEntity
import shared.translator.framework.ModelRelation
import shared.translator.framework.ModelSavedQuery
import shared.translator.framework.SavedQueryBody

/**
 * Adapts a metadata [Model] to the query-translator's [ModelHandle] read surface
 * (db schema only — that's what SQL query parsing needs). Used by [QueryParseWorker]
 * to parse stored queries in-process against the model that owns them. Snapshot-stable:
 * construct one per parse pass over a fixed [Model].
 *
 * Limitations: ER (`er.entity.*`) tables aren't exposed (ER→DB translation would need
 * them — out of scope for the Section F parse worker, which targets SQL queries); column
 * types are mapped to the DSL [SurfaceType] only (no `PhysicalType` round-trip — the
 * domain `DbColumn.dataType` is an opaque string).
 */
class MetadataModelHandle(
    private val model: Model,
) : ModelHandle {
    private val dbSchemas: List<DbSchema> = model.schemas.values.filterIsInstance<DbSchema>()

    override fun tables(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelTable> {
        if (schemaCode != SchemaCode.DB) return emptyMap()
        val out = LinkedHashMap<QualifiedName, ModelTable>()
        for (s in dbSchemas) {
            if (!s.namespace.equals(namespace, ignoreCase = true)) continue
            for ((qn, t) in s.tables) {
                out[qn.toProto()] = ModelTable(qn.toProto(), t.columns.map { it.toModelColumn() }, t.primaryKey)
            }
            for ((qn, v) in s.views) {
                out[qn.toProto()] = ModelTable(qn.toProto(), v.columns.map { it.toModelColumn() })
            }
        }
        return out
    }

    override fun columns(tableQname: QualifiedName): List<ModelColumn> {
        val domainQname = tableQname.toDomain()
        for (s in dbSchemas) {
            s.tables[domainQname]?.let { return it.columns.map { c -> c.toModelColumn() } }
            s.views[domainQname]?.let { return it.columns.map { c -> c.toModelColumn() } }
        }
        return emptyList()
    }

    override fun foreignKeys(): List<ModelForeignKey> =
        dbSchemas.flatMap { it.foreignKeys.values }.map {
            ModelForeignKey(from = it.fromColumns.map { c -> c.toProto() }, to = it.toColumns.map { c -> c.toProto() })
        }

    override fun entities(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelEntity> = emptyMap()

    override fun attributes(entityQname: QualifiedName): List<ModelAttribute> = emptyList()

    override fun relations(): List<ModelRelation> = emptyList()

    override fun entityMapping(entityQname: QualifiedName): EntityMapping? = null

    override fun savedQueries(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelSavedQuery> = emptyMap()

    override fun savedQueryBody(queryQname: QualifiedName): SavedQueryBody =
        error("Not implemented in MetadataModelHandle")

    override fun currentVersion(): String = model.version.value

    override fun namespaces(schemaCode: SchemaCode): Set<String> =
        when (schemaCode) {
            SchemaCode.DB -> dbSchemas.mapTo(mutableSetOf()) { it.namespace }
            SchemaCode.ER -> emptySet()
            SchemaCode.OBJ -> emptySet()
            else -> emptySet()
        }

    // Column qnames are stored table-qualified ("customers.id"); the column's name within its
    // table is the segment after the last dot.
    private fun DbColumn.toModelColumn(): ModelColumn =
        ModelColumn(
            name = qname.name.substringAfterLast('.'),
            surfaceType = surfaceTypeOf(dataType),
            nullable = nullable,
        )
}

private val INT_TYPES = setOf("int", "integer", "bigint", "smallint", "tinyint", "long", "int4", "int8")
private val TEXT_TYPES =
    setOf("text", "varchar", "nvarchar", "char", "nchar", "string", "clob", "uniqueidentifier", "ntext")
private val FLOAT_TYPES = setOf("decimal", "numeric", "float", "double", "real", "money", "smallmoney", "number")
private val BOOL_TYPES = setOf("bool", "boolean", "bit")
private val DATETIME_TYPES =
    setOf("date", "time", "datetime", "datetime2", "timestamp", "smalldatetime", "datetimeoffset")

/** Best-effort mapping from a metadata `DbColumn.dataType` string to a DSL [SurfaceType]. */
internal fun surfaceTypeOf(dataType: String): SurfaceType {
    val base =
        dataType
            .trim()
            .substringBefore('(')
            .substringBefore(' ')
            .lowercase()
    return when (base) {
        in INT_TYPES -> SurfaceType.INT
        in FLOAT_TYPES -> SurfaceType.FLOAT
        in BOOL_TYPES -> SurfaceType.BOOL
        in DATETIME_TYPES -> SurfaceType.DATETIME
        in TEXT_TYPES -> SurfaceType.TEXT
        else -> SurfaceType.TEXT
    }
}
