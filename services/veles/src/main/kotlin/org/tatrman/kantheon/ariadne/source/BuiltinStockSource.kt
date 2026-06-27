package org.tatrman.kantheon.ariadne.source

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.kantheon.ariadne.model.Role
import org.tatrman.ttr.parser.model.RoleDef
import org.tatrman.ttr.semantics.StockLoader

/**
 * Phase 2.2 — emits the stock conceptual-role vocabulary so the metadata
 * service has `cnc.role.fact` etc. defined even before any user `.ttr` file
 * loads. Always registered ahead of user sources at boot.
 *
 * The qnames published here are flagged on the snapshot's [SourceSnapshot.protectedQnames];
 * the [org.tatrman.kantheon.ariadne.reconcile.ModelReconciler] surfaces a load error if a
 * lower-priority source attempts to redefine them.
 *
 * grammar-master Phase 2.8 — the stock content is no longer bundled here; it is
 * the single source of truth inside the published `org.tatrman:ttr-semantics`
 * artifact and is loaded via [StockLoader.load]. This class is now a thin
 * adapter that wraps those `RoleDef`s in ai-platform's `SourceSnapshot` shape.
 */
class BuiltinStockSource(
    private val resourcePath: String = "org.tatrman:ttr-semantics!/builtin/cnc-stock-roles.ttr",
    private val priority: Int = Int.MAX_VALUE,
) : ModelSource {
    override fun load(): SourceSnapshot {
        val definitions = StockLoader.load()
        if (definitions.isEmpty()) {
            return SourceSnapshot(
                sourceId = SOURCE_ID,
                priority = priority,
                version = "missing",
                errors =
                    listOf(
                        LoadWarning(
                            sourceId = SOURCE_ID,
                            file = resourcePath,
                            line = -1,
                            column = -1,
                            message = "Built-in stock roles could not be loaded from org.tatrman:ttr-semantics",
                        ),
                    ),
            )
        }

        val roles = mutableMapOf<QualifiedName, Role>()
        for (def in definitions) {
            if (def !is RoleDef) continue
            val qn =
                QualifiedName
                    .newBuilder()
                    .setPackage("cnc")
                    .setSchemaCode(
                        try {
                            org.tatrman.plan.v1.SchemaCode
                                .valueOf("cnc".uppercase())
                        } catch (
                            e: Exception,
                        ) {
                            org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED
                        },
                    ).setNamespace("role")
                    .setName(def.name)
                    .build()
            roles[qn] =
                Role(
                    internalId = "cnc.cnc.role:cnc.cnc.role.${def.name}",
                    qname = qn,
                    description = def.description ?: "",
                    tags = def.tags,
                    sourceFile = resourcePath,
                    label = def.label.toLocalizedText(),
                )
        }

        // Expose the parsed stock-role definitions as a LoadedFile so the
        // ReferenceResolutionPass symbol table includes them. Without this, the
        // resolver's auto-import (`cnc.*`) step has nothing to match and a user
        // `.ttr` referencing a bare stock role (`roles: [fact]`) gets a false
        // `ttr/unimported-reference`. schemaCode `cnc` makes these defs eligible
        // for auto-import; namespace `role` must match the registry qname above
        // (`cnc.cnc.role.<name>`) so the resolved qname is canonical.
        val stockFile =
            LoadedFile(
                storageFile = StorageFile(path = resourcePath, sizeBytes = 0L),
                computedPackage = "cnc",
                declaredPackage = "cnc",
                imports = emptyList(),
                definitions = definitions,
                schemaCode = "cnc",
                namespace = "role",
            )

        return SourceSnapshot(
            sourceId = SOURCE_ID,
            priority = priority,
            version = "stock-v1",
            roles = roles,
            protectedQnames = roles.keys,
            loadedFiles = listOf(stockFile),
        )
    }

    companion object {
        const val SOURCE_ID = "builtin-stock-roles"
        val STOCK_ROLE_NAMES: Set<String> = setOf("fact", "dimension", "structural", "master", "transaction", "bridge")
    }
}
