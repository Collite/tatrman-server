// SPDX-License-Identifier: Apache-2.0
package org.tatrman.charon.core

import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

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
        private val log = LoggerFactory.getLogger(ConnectionSecretResolver::class.java)

        /**
         * The provider registered via `ServiceLoader`, or the env-var default
         * ([EnvSecretResolver]) when none is registered. Discovered once at
         * registry construction — a resolver is not hot-swapped mid-pod.
         *
         * **CH-D8 — mounted plugin (`pluginDir`).** The open image carries no
         * commercial adapter; a deployment binds one by **mounting its jar** into
         * a plugin directory (env `CHARON_PLUGIN_DIR`) rather than baking it into
         * the image. When [pluginDir] holds `*.jar`s they load through an
         * **isolated child [URLClassLoader]** (parent = this class's loader, so the
         * SPI type identity is shared) and `ServiceLoader` runs over it — which
         * also picks up any provider already on the app classpath. `null` / an
         * absent / an empty dir ⇒ the app classloader only, i.e. today's exact
         * behaviour. Mirrors `svarog/PluginHost.fromPluginDir` (the house pattern:
         * open interface, closed plugin). A jar whose provider fails to
         * instantiate is **skipped with a warning** — never a silent env fallback.
         *
         * **Always logs which resolver won**, because the fallback is silent by
         * design and its failure mode is confusing: a deployment that *ships* the
         * CH-P2 platform adapter but mis-mounts it (empty/absent plugin dir, or no
         * `META-INF/services/…ConnectionSecretResolver` entry in the jar) falls
         * back to env binding, finds no `TTR_CONN_*`, and surfaces as "connections
         * degraded" rather than "adapter missing". The boot line is how an operator
         * tells those two apart. If more than one provider is registered the winner
         * is `ServiceLoader` order — arbitrary — so that case is logged as a warning
         * naming every candidate.
         */
        fun discover(
            pluginDir: Path? = null,
            requireProvider: Boolean = false,
        ): ConnectionSecretResolver {
            val providers = loadProviders(pluginClassLoader(pluginDir))
            return when {
                providers.isEmpty() -> {
                    // F5 (review-074) other half — CH-P2. A deployment that MUST resolve credentials from a
                    // real secret store (the platform's cz.tatrman:secrets-spi adapter) sets `requireProvider`
                    // so a mis-packaged adapter (no META-INF/services entry) fails the pod CLOSED at boot,
                    // instead of silently falling back to env binding, finding no TTR_CONN_*, and surfacing
                    // as the ambiguous "connections degraded".
                    check(!requireProvider) {
                        "connection secrets: require-secret-resolver is set but no ServiceLoader " +
                            "ConnectionSecretResolver provider is registered — refusing to fall back to the " +
                            "env-var default (${EnvSecretResolver::class.java.name}). Package the platform " +
                            "secrets adapter's META-INF/services/${ConnectionSecretResolver::class.java.name} entry."
                    }
                    log.info(
                        "connection secrets: no ServiceLoader provider registered — using the env-var default ({})",
                        EnvSecretResolver::class.java.name,
                    )
                    EnvSecretResolver
                }
                providers.size == 1 -> {
                    log.info("connection secrets: resolver = {} (ServiceLoader)", providers[0]::class.java.name)
                    providers[0]
                }
                else -> {
                    log.warn(
                        "connection secrets: {} providers registered {} — ServiceLoader order decides; using {}",
                        providers.size,
                        providers.map { it::class.java.name },
                        providers[0]::class.java.name,
                    )
                    providers[0]
                }
            }
        }

        /**
         * The classloader `ServiceLoader` runs over: an isolated child [URLClassLoader] over the `*.jar`s in
         * [pluginDir] (parent = this class's loader, so the [ConnectionSecretResolver] type is shared), or —
         * for `null` / an absent / an empty dir — this class's own loader (today's behaviour exactly).
         */
        private fun pluginClassLoader(pluginDir: Path?): ClassLoader {
            val self = ConnectionSecretResolver::class.java.classLoader
            if (pluginDir == null) return self
            if (!pluginDir.exists() || !pluginDir.isDirectory()) {
                log.info("connection secrets: plugin dir {} is absent — app classloader only", pluginDir)
                return self
            }
            val jars = pluginDir.listDirectoryEntries("*.jar").map { it.toUri().toURL() }
            if (jars.isEmpty()) {
                log.info("connection secrets: plugin dir {} has no jars — app classloader only", pluginDir)
                return self
            }
            log.info("connection secrets: loading resolver plugin(s) from {} ({} jar(s))", pluginDir, jars.size)
            return URLClassLoader(jars.toTypedArray(), self)
        }

        /**
         * Load every registered provider off [loader], **skipping** (with a warning) any whose class is
         * missing or fails to instantiate — a broken plugin jar must never crash discovery, and (with
         * `requireProvider`) it correctly leaves the pod to fail closed rather than silently fall back.
         */
        private fun loadProviders(loader: ClassLoader): List<ConnectionSecretResolver> {
            val providers = mutableListOf<ConnectionSecretResolver>()
            val iterator = ServiceLoader.load(ConnectionSecretResolver::class.java, loader).iterator()
            while (iterator.hasNext()) {
                try {
                    providers += iterator.next()
                } catch (e: ServiceConfigurationError) {
                    log.warn("connection secrets: skipping a resolver plugin that failed to load: {}", e.message)
                }
            }
            return providers
        }
    }
}

/** The default provider: resolves against the process environment (`${ENV}`). */
object EnvSecretResolver : ConnectionSecretResolver {
    override fun resolve(name: String): String? = System.getenv(name)
}
