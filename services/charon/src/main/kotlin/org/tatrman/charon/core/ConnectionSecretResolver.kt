// SPDX-License-Identifier: Apache-2.0
package org.tatrman.charon.core

import java.util.ServiceLoader

/**
 * The open connection-secret resolution port (CH-D3).
 *
 * `DbTable` connections in `connections.yaml` carry `${VAR}` credential tokens
 * (contracts §4). Open Charon resolves each token through this port, whose
 * **default is env-var binding** ([EnvSecretResolver]) — the original `${ENV}`
 * plaintext mode, dependency-free.
 *
 * A deployment that resolves credentials from a real secret store registers a
 * provider via `ServiceLoader` (`META-INF/services/…ConnectionSecretResolver`).
 * That is exactly what the **platform's `cz.tatrman:secrets-spi` adapter does at
 * CH-P2** — it binds this port onto the hall's `TTR_CONN_*` least-exposure
 * resolution, so a connection secret resolves the same way whether a worker or
 * Charon consumes it. **No commercial dependency lives in the open service**:
 * the SPI wiring (`fromSecrets`, `TransferSecretInjector`, the `SecretStore`
 * registry) is the adapter's, not Charon's.
 *
 * Returning `null` leaves a token unresolved; [ConnectionRegistry] then **skips
 * that one connection (degraded)** rather than loading a blank credential
 * (plan §4 Stage 2.3 — one broken DB never gates the pod).
 */
fun interface ConnectionSecretResolver {
    /** Resolve the token inside a `${…}` (e.g. `TTR_CONN_DB`) to its value, or
     *  null to leave it unresolved. */
    fun resolve(name: String): String?

    companion object {
        /**
         * The provider registered via `ServiceLoader`, or the env-var default
         * ([EnvSecretResolver]) when none is registered. Discovered once at
         * registry construction — a resolver is not hot-swapped mid-pod.
         */
        fun discover(): ConnectionSecretResolver =
            ServiceLoader.load(ConnectionSecretResolver::class.java).firstOrNull() ?: EnvSecretResolver
    }
}

/** The default provider: resolves against the process environment (`${ENV}`). */
object EnvSecretResolver : ConnectionSecretResolver {
    override fun resolve(name: String): String? = System.getenv(name)
}
