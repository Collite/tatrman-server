package org.tatrman.geo.roundtrip

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelForeignKey
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.framework.ModelSavedQuery
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SavedQueryBody
import org.tatrman.translator.framework.SurfaceType

/**
 * Minimal DB-schema [ModelHandle] for the A8.5 sql_preview round-trip: just enough tables for the
 * Translator to resolve a wrapped recipe (`SELECT 1 FROM <fact> AS t [JOIN …] WHERE <condition>`).
 * Table names are deliberately non-reserved (`Sale`, not `Transaction`).
 */
class GroundingModelHandle(
    private val tables: List<ModelTable>,
) : ModelHandle {
    override fun tables(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelTable> =
        tables
            .filter { it.qname.schemaCode == schemaCode && it.qname.namespace == namespace }
            .associateBy { it.qname }

    override fun columns(tableQname: QualifiedName): List<ModelColumn> =
        tables.firstOrNull { it.qname == tableQname }?.columns ?: emptyList()

    override fun foreignKeys(): List<ModelForeignKey> = emptyList()

    override fun entities(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelEntity> = emptyMap()

    override fun attributes(entityQname: QualifiedName) = emptyList<Nothing>()

    override fun relations(): List<ModelRelation> = emptyList()

    override fun entityMapping(entityQname: QualifiedName) = null

    override fun savedQueries(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelSavedQuery> = emptyMap()

    override fun savedQueryBody(queryQname: QualifiedName): SavedQueryBody =
        error("no saved queries in the round-trip fixture")

    override fun currentVersion(): String = "chrono-roundtrip-v0"

    override fun namespaces(schemaCode: SchemaCode): Set<String> =
        if (schemaCode == SchemaCode.DB) tables.mapTo(mutableSetOf()) { it.qname.namespace } else emptySet()

    companion object {
        private fun dbTable(
            name: String,
            columns: List<Pair<String, SurfaceType>>,
        ): ModelTable =
            ModelTable(
                qname =
                    QualifiedName
                        .newBuilder()
                        .setSchemaCode(SchemaCode.DB)
                        .setNamespace("dbo")
                        .setName(name)
                        .build(),
                columns = columns.map { (n, t) -> ModelColumn(n, t, nullable = true) },
            )

        /** POI `Store(lat, lon, store_name)` — float coords + a name column for the POI-join round-trip. */
        fun storeModel(): GroundingModelHandle =
            GroundingModelHandle(
                listOf(
                    dbTable(
                        "Store",
                        listOf(
                            "lat" to SurfaceType.FLOAT,
                            "lon" to SurfaceType.FLOAT,
                            "store_name" to SurfaceType.TEXT,
                        ),
                    ),
                ),
            )
    }
}
