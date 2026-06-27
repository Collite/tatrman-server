package org.tatrman.kantheon.ariadne.source

import org.slf4j.Logger

/**
 * Surface model load/reconcile diagnostics so they're discoverable in logs.
 *
 * Reconcile "errors" (unresolved references, kind/package mismatches, …) are model-content problems
 * in the source, not application failures — the model is still served. So they're logged at **WARN**:
 * ERROR is reserved for actual app errors. Each issue carries `source:file:line:col — message` so the
 * offending definition can be found. Reconcile "warnings" are logged at WARN too, tagged distinctly.
 *
 * Called after the initial load and after every refresh swap, alongside the INFO one-line summary
 * (`… warnings=N errors=M`) so the counts on that line are explained by the lines beneath it.
 */
fun Logger.logModelLoadIssues(
    errors: List<LoadWarning>,
    warnings: List<LoadWarning>,
) {
    errors.forEach { w ->
        warn("model-load error [{}] {}:{}:{} — {}", w.sourceId, w.file, w.line, w.column, w.message)
    }
    warnings.forEach { w ->
        warn("model-load warning [{}] {}:{}:{} — {}", w.sourceId, w.file, w.line, w.column, w.message)
    }
}
