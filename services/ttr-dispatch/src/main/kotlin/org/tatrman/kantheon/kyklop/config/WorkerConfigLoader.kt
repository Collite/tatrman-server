package org.tatrman.kantheon.kyklop.config

import com.typesafe.config.Config
import org.slf4j.LoggerFactory

/**
 * Parses the `kyklop.workers` HOCON list into ordered [WorkerSlot]s. A slot whose `endpoint`
 * is empty or absent (e.g. when its env-var override isn't set in the running environment) is
 * skipped — keeps a single conf shape usable across local dev and deployments without forcing
 * every consumer to set every worker env var.
 *
 * DF-D04 (Phase 06 C1) — both the MSSQL and Polars worker slots are declared in
 * `application.conf`; deployments populate the matching env vars to activate them.
 */
internal data class WorkerSlot(
    val endpoint: String,
    val roleHint: String,
)

internal object WorkerConfigLoader {
    private val log = LoggerFactory.getLogger(WorkerConfigLoader::class.java)

    fun load(config: Config): List<WorkerSlot> {
        if (!config.hasPath(PATH)) return emptyList()
        return config
            .getConfigList(PATH)
            .mapNotNull { entry ->
                val endpoint =
                    if (entry.hasPath("endpoint")) entry.getString("endpoint").trim() else ""
                val roleHint = if (entry.hasPath("role-hint")) entry.getString("role-hint") else ""
                if (endpoint.isEmpty()) {
                    log.warn("Skipping kyklop.workers entry with empty endpoint (role-hint='{}')", roleHint)
                    null
                } else {
                    WorkerSlot(endpoint = endpoint, roleHint = roleHint)
                }
            }
    }

    private const val PATH = "kyklop.workers"
}
