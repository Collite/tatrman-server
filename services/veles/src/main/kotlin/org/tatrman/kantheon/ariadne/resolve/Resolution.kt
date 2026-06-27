package org.tatrman.kantheon.ariadne.resolve

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.ttr.parser.model.ImportStatement

/**
 * Outcome of resolving one cross-reference. ai-platform's proto-shaped result
 * type, kept stable across the resolver consolidation: [ReferenceResolutionPass]
 * produces it and the reconciler/export consume it.
 *
 * (Previously declared alongside the hand-rolled `ReferenceResolver`; that
 * resolver was deleted when the pass switched to [PublishedResolverAdapter] over
 * `org.tatrman.ttr.semantics`, so these types now live on their own.)
 */
sealed interface Resolution {
    data class Resolved(
        val qualifiedName: QualifiedName,
    ) : Resolution

    data class Diagnostic(
        val code: String,
        val message: String,
    ) : Resolution
}

/**
 * Context for resolving a reference: the referring file's package, imports, and
 * (resolved) schema/namespace.
 *
 * Step 1 (lexical/file-local scope) is intentionally NOT modelled here. The load
 * pipeline (Stage 07) holds the file's own definitions and checks them before
 * this pass runs, so `nameAttribute`/`codeAttribute` never reach the resolver.
 */
data class ResolutionContext(
    val packageName: String?,
    val imports: List<ImportStatement>,
    val schemaCode: String? = null,
    val resolvedNamespace: String? = null,
)
