package org.tatrman.kantheon.proteus.model

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import shared.translator.framework.EntityMapping
import shared.translator.framework.ModelAttribute
import shared.translator.framework.ModelColumn
import shared.translator.framework.ModelEntity
import shared.translator.framework.ModelForeignKey
import shared.translator.framework.ModelHandle
import shared.translator.framework.ModelRelation
import shared.translator.framework.ModelSavedQuery
import shared.translator.framework.ModelTable
import shared.translator.framework.SavedQueryBody
import shared.translator.framework.SurfaceType

/**
 * Tiny boot-time fixture model used when `proteus.use-fixture-model = true`.
 * Lets the service start up and serve trivial requests when no metadata service
 * is reachable (development, smoke tests, CI). Production deployments fall
 * back to this only if the metadata client fails to load — see Application.kt.
 */
object BootFixtureModel {
    private fun qname(
        schema: SchemaCode,
        ns: String,
        name: String,
    ): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(schema)
            .setNamespace(ns)
            .setName(name)
            .build()

    private val qsubjekt =
        ModelTable(
            qname = qname(SchemaCode.DB, "dbo", "QSUBJEKT"),
            columns =
                listOf(
                    ModelColumn("id", SurfaceType.INT, nullable = false),
                    ModelColumn("name", SurfaceType.TEXT, nullable = true),
                ),
            primaryKey = listOf("id"),
        )

    private val customerEntity =
        ModelEntity(
            qname = qname(SchemaCode.ER, "entity", "customer"),
            attributes =
                listOf(
                    ModelAttribute("id", SurfaceType.INT, nullable = false, isKey = true),
                    ModelAttribute("name", SurfaceType.TEXT, nullable = true),
                ),
        )

    fun handle(): ModelHandle =
        object : ModelHandle {
            override fun tables(
                schemaCode: SchemaCode,
                namespace: String,
            ): Map<QualifiedName, ModelTable> =
                listOf(qsubjekt)
                    .filter { it.qname.schemaCode == schemaCode && it.qname.namespace == namespace }
                    .associateBy { it.qname }

            override fun columns(tableQname: QualifiedName): List<ModelColumn> =
                if (tableQname == qsubjekt.qname) qsubjekt.columns else emptyList()

            override fun foreignKeys(): List<ModelForeignKey> = emptyList()

            override fun entities(
                schemaCode: SchemaCode,
                namespace: String,
            ): Map<QualifiedName, ModelEntity> =
                listOf(customerEntity)
                    .filter { it.qname.schemaCode == schemaCode && it.qname.namespace == namespace }
                    .associateBy { it.qname }

            override fun attributes(entityQname: QualifiedName): List<ModelAttribute> =
                if (entityQname == customerEntity.qname) customerEntity.attributes else emptyList()

            override fun relations(): List<ModelRelation> = emptyList()

            override fun entityMapping(entityQname: QualifiedName): EntityMapping? =
                if (entityQname == customerEntity.qname) {
                    EntityMapping.ToTable(table = qsubjekt.qname, whereFilter = null)
                } else {
                    null
                }

            override fun savedQueries(
                schemaCode: SchemaCode,
                namespace: String,
            ): Map<QualifiedName, ModelSavedQuery> = emptyMap()

            override fun savedQueryBody(queryQname: QualifiedName): SavedQueryBody =
                error("No saved queries in BootFixtureModel")

            override fun currentVersion(): String = "boot-fixture-v0"

            override fun namespaces(schemaCode: SchemaCode): Set<String> =
                when (schemaCode) {
                    SchemaCode.DB -> setOf("dbo")
                    SchemaCode.ER -> setOf("entity")
                    SchemaCode.OBJ -> setOf("query")
                    else -> emptySet()
                }
        }
}
