package org.tatrman.kantheon.ariadne.resolve

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.kantheon.ariadne.source.LoadedFile
import org.tatrman.ttr.semantics.ResolutionContext as TtrCtx
import org.tatrman.ttr.semantics.ResolutionResult as TtrResult
import org.tatrman.ttr.semantics.ResolutionStep
import org.tatrman.ttr.semantics.Resolver as TtrResolver
import org.tatrman.ttr.semantics.SymbolEntry
import org.tatrman.ttr.semantics.SymbolTable as TtrSymbolTable

/**
 * Resolver-consolidation Phase B/C — replaces ai-platform's hand-maintained
 * symbol table + reference resolver, delegating the symbol-table build and
 * per-reference resolution to the published
 * `org.tatrman.ttr.semantics.{SymbolTable, Resolver}`.
 *
 * The adapter keeps ai-platform's proto `QualifiedName` identity downstream: it
 * converts each resolved [SymbolEntry] back to a proto `QualifiedName` via
 * [toProtoQName], mirroring the former resolver's identity rules exactly (the
 * package is dropped for everything except auto-imported stock roles — see
 * contracts §2.2). `ReferenceResolutionPass` keeps its orchestration, reference
 * collection, and import/circular diagnostics, and tracks used-imports via
 * [wildcardMatchCount] / [namedTargetExists] (contracts §2.4).
 */
class PublishedResolverAdapter private constructor(
    private val resolver: TtrResolver,
    private val byQname: Map<String, SymbolEntry>,
) {
    companion object {
        fun build(files: List<LoadedFile>): PublishedResolverAdapter {
            val table = TtrSymbolTable()
            for (f in files) {
                table.upsertDocument(
                    uri = f.storageFile.path,
                    definitions = f.definitions,
                    schemaCode = f.schemaCode,
                    namespace = f.namespace,
                    // contracts §2.1 — the declared package (dropped again in
                    // toProtoQName for non-stock entries; the stock LoadedFile
                    // declares package "cnc" → qname cnc.cnc.role.<name>).
                    packageName = f.declaredPackage ?: "",
                )
            }
            val byQname = table.all().associateBy { it.qname }
            return PublishedResolverAdapter(TtrResolver(table), byQname)
        }
    }

    /** A resolution plus the step that won (null when unresolved). */
    data class ResolveDetail(
        val resolution: Resolution,
        val viaStep: ResolutionStep?,
    )

    /** Drop-in for the former `ReferenceResolver.resolve(ref, ctx)`. */
    fun resolve(
        ref: String,
        ctx: ResolutionContext,
    ): Resolution = resolveDetailed(ref, ctx).resolution

    /**
     * Like [resolve] but also returns the winning [ResolutionStep] (null when
     * unresolved), so `ReferenceResolutionPass` can emit diagnostics from the
     * resolution while tracking used-imports separately (contracts §2.4).
     */
    fun resolveDetailed(
        ref: String,
        ctx: ResolutionContext,
    ): ResolveDetail {
        // (1) bare-import guard — a non-wildcard import with < 3 dotted segments
        //     is illegal regardless of the ref (the published resolver assumes
        //     the parser rejects single-segment imports).
        for (imp in ctx.imports) {
            if (!imp.wildcard && imp.target.split(".").size < 3) return ResolveDetail(unimported(ref), null)
        }
        // (2) delegate to the published resolver
        val result =
            resolver.resolveReference(
                TtrResolver.Ref(ref, ref.split(".")),
                TtrCtx(
                    schemaCode = ctx.schemaCode ?: "db",
                    namespace = ctx.resolvedNamespace ?: "",
                    enclosingQname = null,
                    imports = ctx.imports,
                    packageName = ctx.packageName,
                ),
            )
        // (3) map back to ai-platform's proto-shaped Resolution
        return when (result) {
            is TtrResult.Resolved -> {
                // The published resolver has a final "fully-qualified-but-unique"
                // step that resolves ANY globally-unique name — including a *bare*
                // name reachable via no import. ai-platform requires bare names to
                // be import-reachable (same-package / named / wildcard / auto-import;
                // the earlier steps already cover the first and last). So when that
                // fallback fires for a single-segment ref, re-check ai-platform
                // import-reachability and downgrade to `unimported` if it is not —
                // otherwise the consolidation would silently relax import discipline
                // (see PackageResolutionSpec's cross-package negative test). The
                // legitimate wildcard case (`import er.x.*` → bare name) also lands
                // here, because the published wildcard step is package-scoped while
                // ai-platform wildcards are schema.namespace-scoped; the
                // reachability re-check keeps it resolved.
                if (result.viaStep == ResolutionStep.FullyQualified &&
                    !ref.contains('.') &&
                    !isBareNameImportReachable(ref, ctx)
                ) {
                    ResolveDetail(unimported(ref), null)
                } else {
                    ResolveDetail(Resolution.Resolved(toProtoQName(result.symbol, result.viaStep)), result.viaStep)
                }
            }
            is TtrResult.Unresolved ->
                when (result.reason) {
                    TtrResult.Reason.Ambiguous -> ResolveDetail(ambiguous(ref), null)
                    TtrResult.Reason.NotFound -> ResolveDetail(unimported(ref), null)
                }
        }
    }

    // ----- used-import tracking (contracts §2.4) -----
    //
    // ai-platform's wildcard imports are schema.namespace-scoped (`import er.x.*`),
    // whereas the published resolver matches wildcard imports by *package*; so a
    // wildcard import that resolves a unique ref does so via the resolver's
    // fully-qualified-unique step, not its wildcard step (see contracts §3.1).
    // The pass therefore cannot read used-imports off `viaStep`. Instead these two
    // helpers query the published symbol table the same way the former
    // `SymbolTable.matchingWildcard` / `byFull` did, keeping the
    // unused-import / wildcard-with-no-matches / duplicate-import diagnostics
    // byte-identical.

    /**
     * Mirrors the former `matchingWildcard(target).count { it.fqn.name == bareName }`.
     * Legacy matched the `schema.namespace` prefix case-INSENSITIVELY and the def
     * name case-sensitively.
     */
    fun wildcardMatchCount(
        wildcardTarget: String,
        bareName: String,
    ): Int =
        byQname.values.count { e ->
            "${e.schemaCode}.${e.namespace}".equals(wildcardTarget, ignoreCase = true) &&
                protoSimpleName(e) == bareName
        }

    /**
     * ai-platform import-reachability for a bare (single-segment) name: reachable
     * iff some wildcard import (schema.namespace-scoped) exposes it, or some named
     * import targets it. Same matching the unused-import tracker uses.
     */
    private fun isBareNameImportReachable(
        ref: String,
        ctx: ResolutionContext,
    ): Boolean =
        ctx.imports.any { imp ->
            if (imp.wildcard) {
                wildcardMatchCount(imp.target, ref) > 0
            } else {
                imp.target.split(".").last() == ref && namedTargetExists(imp.target)
            }
        }

    /**
     * Mirrors the former named-import check `byFull(target) != null`: a def exists
     * at the target qname. Legacy normalised only the schema segment (lowercased);
     * namespace + name are case-sensitive.
     */
    fun namedTargetExists(target: String): Boolean {
        val parts = target.split(".")
        if (parts.size < 3) return false
        val normTarget = (listOf(parts[0].lowercase()) + parts.drop(1)).joinToString(".")
        return byQname.values.any { e ->
            "${e.schemaCode.lowercase()}.${e.namespace}.${protoSimpleName(e)}" == normTarget
        }
    }

    private fun protoSimpleName(e: SymbolEntry): String =
        e.parent
            ?.let { "${byQname[it]?.name ?: it.substringAfterLast('.')}.${e.name}" }
            ?: e.name

    /**
     * Convert a published [SymbolEntry] to ai-platform's proto [QualifiedName]
     * (contracts §2.2). Normative rules:
     *  1. package = "cnc" iff resolved via the auto-import step, else "" (the
     *     legacy resolver stamps `cnc` only on auto-imported stock roles).
     *  2. schemaCode = enum of `e.schemaCode` uppercased; UNSPECIFIED on miss.
     *  3. namespace = `e.namespace` (the file namespace, added in modeler 0.3.0).
     *  4. name = `e.name` for a top-level def; `"<parentName>.<e.name>"` for a
     *     nested def, where `<parentName>` is the parent entry's simple name.
     */
    private fun toProtoQName(
        e: SymbolEntry,
        step: ResolutionStep,
    ): QualifiedName {
        val pkg = if (step == ResolutionStep.AutoImport) "cnc" else ""
        return QualifiedName
            .newBuilder()
            .setPackage(pkg)
            .setSchemaCode(
                runCatching { SchemaCode.valueOf(e.schemaCode.uppercase()) }
                    .getOrDefault(SchemaCode.SCHEMA_CODE_UNSPECIFIED),
            ).setNamespace(e.namespace)
            .setName(protoSimpleName(e))
            .build()
    }

    private fun unimported(ref: String): Resolution =
        Resolution.Diagnostic(
            "ttr/unimported-reference",
            "'$ref' is not reachable from this file's imports",
        )

    private fun ambiguous(ref: String): Resolution =
        Resolution.Diagnostic(
            "ttr/ambiguous-reference",
            "'$ref' is ambiguous — multiple candidates found",
        )
}
