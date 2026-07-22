// SPDX-License-Identifier: Apache-2.0
package cz.tatrman.charon.core

import cz.tatrman.secrets.spi.SecretRef
import cz.tatrman.secrets.spi.SecretStoreRegistry

/**
 * A Charon transfer secret pre-flight failure (contracts §17/§21, `PLT-SEC-001`). Raised BEFORE any
 * source/target endpoint is touched — nothing moved — so the caller may retry per the transfer's policy.
 */
class SecretPreflightException(
    val code: String,
    val connection: String,
    cause: Throwable? = null,
) : RuntimeException("transfer secret pre-flight failed for `$connection`: $code", cause)

/**
 * Dispatch-time secret injection for a transfer's **source×target** connection pair (contracts §17, H-5).
 *
 * The Charon-path twin of the hall's `DispatchInjector`: it resolves ONLY the transfer's declared
 * connections to material AT TRANSFER TIME and returns them as `TTR_CONN_*` env. The material lives only
 * in the returned map — each [cz.tatrman.secrets.spi.SecretMaterial] is zeroized here, so nothing sits at
 * rest, nothing is logged, nothing rides the wire. A store unreachable at resolution is a retryable
 * [SecretPreflightException] (`PLT-SEC-001`) raised before any I/O. Only the declared connections are
 * resolved (H-5 least exposure — an undeclared connection is never touched).
 *
 * Built on `:shared:secrets-spi` — never a dependency on the hall service (same SPI, own injector; the P2
 * dependency-rule gate forbids service→service). The `TTR_CONN_*` env contract is identical to the hall's,
 * so a connection secret is resolved the same way whether a worker or Charon consumes it.
 */
class TransferSecretInjector(
    private val secrets: SecretStoreRegistry,
) {
    /**
     * Resolve [connectionRefs] (the transfer's declared connections, `TTR_CONN_*` name → ref) to a
     * `name → material` env map. Keys are the verbatim `TTR_CONN_*` names the endpoints consume.
     */
    fun inject(connectionRefs: Map<String, SecretRef>): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        for ((name, ref) in connectionRefs) {
            val material =
                try {
                    secrets.resolve(ref)
                } catch (e: Exception) {
                    // Store unreachable/failed at resolution → transfer PRE-FLIGHT failure, before any I/O.
                    throw SecretPreflightException("PLT-SEC-001", name, e)
                }
            try {
                // R2-12 convention (pinned in PL-P2.S6): one secret file per connection holding the BARE
                // value (the DSN / connection string); `asString()` is that whole file.
                env[name] = material.asString()
            } finally {
                material.zeroize() // the material outlives only the transfer env we just built
            }
        }
        return env
    }
}
