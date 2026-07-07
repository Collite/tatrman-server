package org.tatrman.kantheon.arges.pipeline

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName

/**
 * Concretizes a validated *logical* PlanNode for a specific engine connection by rewriting every
 * table qualified-name namespace to the connection's engine default schema (charon/architecture §6 —
 * "workers advertise `database` so the dispatcher can concretize logical qnames to the connection's
 * database / default schema").
 *
 * v1 models carry a single logical DB namespace — the translator's default `dbo` token, an MSSQL-ism.
 * The physical Postgres tables live in the connection's `default-schema` (e.g. `public`). Without this
 * rewrite the worker unparses `dbo.store_sales`, which does not exist in Postgres (`relation
 * "dbo.store_sales" does not exist`). Rewriting the namespace to the connection schema emits
 * `public.store_sales`, which resolves.
 *
 * Only the namespace is touched — `schema_code`, `name`, `package`, columns, and every other node
 * field are preserved. The walk is total over the PlanNode oneof so nested scans (under joins,
 * aggregates, unions, subqueries, …) are all concretized.
 */
object PlanSchemaConcretizer {
    /** Returns [plan] with every table-scan namespace set to [schema]. A blank [schema] is a no-op. */
    fun withSchema(
        plan: PlanNode,
        schema: String,
    ): PlanNode {
        if (schema.isEmpty()) return plan
        val b = plan.toBuilder()
        when (plan.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN ->
                b.tableScanBuilder.table = plan.tableScan.table.withNamespace(schema)
            PlanNode.NodeCase.SCAN ->
                b.scanBuilder.setObject(plan.scan.getObject().withNamespace(schema))
            PlanNode.NodeCase.PROJECT ->
                b.projectBuilder.input = withSchema(plan.project.input, schema)
            PlanNode.NodeCase.FILTER ->
                b.filterBuilder.input = withSchema(plan.filter.input, schema)
            PlanNode.NodeCase.JOIN -> {
                b.joinBuilder.left = withSchema(plan.join.left, schema)
                b.joinBuilder.right = withSchema(plan.join.right, schema)
            }
            PlanNode.NodeCase.AGGREGATE ->
                b.aggregateBuilder.input = withSchema(plan.aggregate.input, schema)
            PlanNode.NodeCase.SORT ->
                b.sortBuilder.input = withSchema(plan.sort.input, schema)
            PlanNode.NodeCase.LIMIT_OFFSET ->
                b.limitOffsetBuilder.input = withSchema(plan.limitOffset.input, schema)
            PlanNode.NodeCase.SUBQUERY ->
                b.subqueryBuilder.subquery = withSchema(plan.subquery.subquery, schema)
            PlanNode.NodeCase.UNION -> {
                val rewritten = plan.union.inputsList.map { withSchema(it, schema) }
                b.unionBuilder.clearInputs().addAllInputs(rewritten)
            }
            // Leaves with no table namespace to concretize.
            PlanNode.NodeCase.VALUES,
            PlanNode.NodeCase.WORKSPACE_REF,
            PlanNode.NodeCase.NODE_NOT_SET,
            -> Unit
        }
        return b.build()
    }

    private fun QualifiedName.withNamespace(ns: String): QualifiedName = toBuilder().setNamespace(ns).build()
}
