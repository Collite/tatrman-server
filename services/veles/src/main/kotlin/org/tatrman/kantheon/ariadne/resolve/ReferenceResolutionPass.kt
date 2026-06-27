package org.tatrman.kantheon.ariadne.resolve

import org.tatrman.kantheon.ariadne.source.LoadedFile
import org.tatrman.kantheon.ariadne.source.LoadWarning
import org.tatrman.ttr.parser.model.Definition
import org.tatrman.ttr.parser.model.EntityDef
import org.tatrman.ttr.parser.model.Er2CncRoleDef
import org.tatrman.ttr.parser.model.Er2DbAttributeDef
import org.tatrman.ttr.parser.model.Er2DbEntityDef
import org.tatrman.ttr.parser.model.Er2DbRelationDef
import org.tatrman.ttr.parser.model.PropertyValue
import org.tatrman.ttr.parser.model.Reference
import org.tatrman.ttr.parser.model.RelationDef

class ReferenceResolutionPass(
    private val sourceId: String,
    private val files: List<LoadedFile>,
) {
    fun run(): ResolutionResult {
        val adapter = PublishedResolverAdapter.build(files)
        val newWarnings = mutableListOf<LoadWarning>()
        val newErrors = mutableListOf<LoadWarning>()
        val resolvedRefs = mutableListOf<ResolvedReference>()

        // A5 Task 5 — per-file import tracking for unused/duplicate/wildcard diagnostics
        val usedImportsPerFile = mutableMapOf<String, MutableSet<Int>>()
        val wildcardMatchCountPerFile = mutableMapOf<String, MutableMap<Int, Int>>()

        for (file in files) {
            val fileImportsUsed = mutableSetOf<Int>()
            val fileWildcardMatches = mutableMapOf<Int, Int>()
            usedImportsPerFile[file.storageFile.path] = fileImportsUsed
            wildcardMatchCountPerFile[file.storageFile.path] = fileWildcardMatches

            val ctx =
                ResolutionContext(
                    packageName = file.computedPackage.ifEmpty { null },
                    imports = file.imports,
                    schemaCode = file.schemaCode,
                    resolvedNamespace = file.namespace,
                )

            // A5 Task 5 — missing-package-declaration (Info): a file whose directory implies a
            // package (computedPackage is the relative dir, per Stage 07) but which does not
            // declare `package X`. Root-level files have an empty computedPackage and are exempt.
            if (file.computedPackage.isNotEmpty() && file.declaredPackage == null) {
                newWarnings.add(
                    LoadWarning(
                        sourceId = sourceId,
                        file = file.storageFile.path,
                        line = -1,
                        column = -1,
                        message = "ttr/missing-package-declaration: file in subdirectory has no package declaration",
                    ),
                )
            }

            for (ref in collectReferences(file.definitions)) {
                val resolution = adapter.resolve(ref.path, ctx)
                resolvedRefs.add(ResolvedReference(file.storageFile.path, ref, resolution))
                if (resolution is Resolution.Diagnostic) {
                    newErrors.add(
                        LoadWarning(
                            sourceId = sourceId,
                            file = file.storageFile.path,
                            line = -1,
                            column = -1,
                            message = "${resolution.code}: ${resolution.message}",
                        ),
                    )
                }
                // Record which import was consulted (for unused-import detection)
                recordUsedImport(adapter, ctx, ref.path, fileImportsUsed, fileWildcardMatches)
            }

            // A5 Task 5 — duplicate-import (W): same import twice in one file
            val seenImports = mutableSetOf<String>()
            for ((idx, imp) in file.imports.withIndex()) {
                val importKey = if (imp.wildcard) "*.${imp.target}" else imp.target
                if (!seenImports.add(importKey)) {
                    newWarnings.add(
                        LoadWarning(
                            sourceId = sourceId,
                            file = file.storageFile.path,
                            line = -1,
                            column = -1,
                            message = "ttr/duplicate-import: '${imp.target}' appears more than once",
                        ),
                    )
                }
            }

            // A5 Task 5 — unused-import (W): import not used by any reference
            for ((idx, imp) in file.imports.withIndex()) {
                if (idx !in fileImportsUsed) {
                    newWarnings.add(
                        LoadWarning(
                            sourceId = sourceId,
                            file = file.storageFile.path,
                            line = -1,
                            column = -1,
                            message = "ttr/unused-import: '${imp.target}' is imported but not used",
                        ),
                    )
                }
            }

            // A5 Task 5 — wildcard-with-no-matches (W): wildcard import matched nothing
            for ((idx, imp) in file.imports.withIndex()) {
                if (imp.wildcard && (fileWildcardMatches[idx] ?: 0) == 0) {
                    newWarnings.add(
                        LoadWarning(
                            sourceId = sourceId,
                            file = file.storageFile.path,
                            line = -1,
                            column = -1,
                            message = "ttr/wildcard-with-no-matches: 'import ${imp.target}.*' matches nothing",
                        ),
                    )
                }
            }
        }

        // A5 Task 5 — circular-package-dependency (W)
        detectCircularDependencies(files, newWarnings)

        return ResolutionResult(
            warnings = newWarnings,
            errors = newErrors,
            resolvedReferences = resolvedRefs,
        )
    }

    private fun recordUsedImport(
        adapter: PublishedResolverAdapter,
        ctx: ResolutionContext,
        refPath: String,
        fileImportsUsed: MutableSet<Int>,
        fileWildcardMatches: MutableMap<Int, Int>,
    ) {
        val bareName = refPath.split(".").lastOrNull() ?: refPath
        for ((idx, imp) in ctx.imports.withIndex()) {
            if (imp.wildcard) {
                val matchCount = adapter.wildcardMatchCount(imp.target, bareName)
                if (matchCount > 0) {
                    fileImportsUsed.add(idx)
                    fileWildcardMatches[idx] = (fileWildcardMatches[idx] ?: 0) + matchCount
                }
            } else {
                val targetParts = imp.target.split(".")
                if (targetParts.last() == bareName && adapter.namedTargetExists(imp.target)) {
                    fileImportsUsed.add(idx)
                }
            }
        }
    }

    private fun detectCircularDependencies(
        files: List<LoadedFile>,
        warnings: MutableList<LoadWarning>,
    ) {
        val packageDeps = mutableMapOf<String, MutableSet<String>>()
        for (file in files) {
            val pkg = file.computedPackage.ifEmpty { continue }
            val deps = packageDeps.getOrPut(pkg) { mutableSetOf() }
            for (imp in file.imports) {
                val impPkg = imp.target.split(".").firstOrNull() ?: continue
                if (impPkg != pkg) deps.add(impPkg)
            }
        }
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()

        fun dfs(
            pkg: String,
            path: List<String>,
        ): Boolean {
            if (pkg in recursionStack) {
                val cycleStart = path.indexOf(pkg)
                val cycle = path.drop(cycleStart) + listOf(pkg)
                warnings.add(
                    LoadWarning(
                        sourceId = sourceId,
                        file = "<reconcile>",
                        line = -1,
                        column = -1,
                        message =
                            "ttr/circular-package-dependency: package cycle detected: ${cycle.joinToString(" -> ")}",
                    ),
                )
                return true
            }
            if (pkg in visited) return false
            visited.add(pkg)
            recursionStack.add(pkg)
            for (dep in packageDeps[pkg] ?: emptySet()) {
                dfs(dep, path + listOf(pkg))
            }
            recursionStack.remove(pkg)
            return false
        }

        for (pkg in packageDeps.keys) {
            dfs(pkg, emptyList())
        }
    }

    private fun collectReferences(definitions: List<Definition>): List<Reference> =
        definitions.flatMap { def ->
            when (def) {
                is EntityDef -> def.roles
                // nameAttribute/codeAttribute are local/lexical references (step 1 scope);
                // they are not cross-package references and are handled by the loader's
                // file-local symbol table, not by this resolution pass.
                is RelationDef -> {
                    val refs = mutableListOf<Reference>()
                    def.from?.let { refs.addAll(extractRefs(listOf(it))) }
                    def.to?.let { refs.addAll(extractRefs(listOf(it))) }
                    refs
                }
                is Er2DbEntityDef -> {
                    val refs = mutableListOf<Reference>()
                    def.entity?.let { refs.add(it) }
                    refs
                }
                is Er2DbAttributeDef -> {
                    val refs = mutableListOf<Reference>()
                    def.attribute?.let { refs.add(it) }
                    refs
                }
                is Er2DbRelationDef -> {
                    val refs = mutableListOf<Reference>()
                    def.relation?.let { refs.add(it) }
                    def.fk?.let { refs.add(it) }
                    refs
                }
                is Er2CncRoleDef -> {
                    val refs = mutableListOf<Reference>()
                    def.entity?.let { refs.add(it) }
                    def.role?.let { refs.add(it) }
                    refs
                }
                else -> emptyList()
            }
        }

    private fun extractRefs(values: List<PropertyValue>): List<Reference> =
        values.mapNotNull { value ->
            when (value) {
                is PropertyValue.IdValue -> value.ref
                else -> null
            }
        }
}

data class ResolvedReference(
    val filePath: String,
    val ref: Reference,
    val resolution: Resolution,
)

data class ResolutionResult(
    val warnings: List<LoadWarning>,
    val errors: List<LoadWarning>,
    val resolvedReferences: List<ResolvedReference>,
)
