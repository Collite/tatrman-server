// SPDX-License-Identifier: Apache-2.0
package shared.ktor

/**
 * Liveness/readiness paths that are excluded from the request log.
 *
 * Kubernetes probes each pod every few seconds, forever. At `CallLogging` level INFO that is one
 * log line per probe per pod — which on a full estate drowns the request log in noise and makes
 * `kubectl logs` useless for the thing you actually opened it for. Worse, it is *expensive*
 * noise: these lines ship through the JSON encoder and the OTLP appender to the log backend.
 *
 * The paths are matched exactly (after stripping any trailing slash), so a real route that merely
 * *starts* with `/health` — say `/healthcheck/detail` — still logs.
 *
 * Override with `KTOR_LOG_PROBE_PATHS`: a comma-separated list of paths to suppress, or the empty
 * string to suppress nothing and log every probe again (useful when debugging a probe that is
 * itself failing, which is the one time you want these lines).
 */
object ProbePaths {
    private val DEFAULT = setOf("/health", "/ready", "/healthz", "/readyz", "/livez", "/metrics")

    val suppressed: Set<String> by lazy {
        val raw = System.getenv("KTOR_LOG_PROBE_PATHS") ?: return@lazy DEFAULT
        raw
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** True when [path] is a probe whose log line should be dropped. */
    fun isProbe(path: String): Boolean = (if (path.length > 1) path.trimEnd('/') else path) in suppressed
}
