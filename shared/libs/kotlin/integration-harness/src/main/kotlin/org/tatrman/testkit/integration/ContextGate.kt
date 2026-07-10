package org.tatrman.testkit.integration

/**
 * The readiness-gate logic, independent of Kotest. Resolves the context's
 * namespace, asserts every readiness check, and returns a [ContextHandle] — or
 * throws [ContextNotReadyException]. It **never provisions**: a missing or
 * not-ready context is a harness/CI error surfaced loudly, not papered over
 * (testing architecture §7). All cluster access is read-only via [ClusterReader].
 */
class ContextGate(
    private val reader: ClusterReader,
) {
    fun open(
        contextName: String,
        explicitNamespace: String? = null,
    ): ContextHandle {
        val namespace =
            explicitNamespace?.takeIf { it.isNotBlank() }
                ?: reader.resolveNamespace(contextName)
                ?: throw ContextNotReadyException(
                    contextName,
                    "no namespace (label ${Fabric8ClusterReader.CONTEXT_LABEL}=$contextName not found, " +
                        "and no -Pnamespace given)",
                )

        val checks = reader.readinessChecks(namespace)
        if (checks.isEmpty()) {
            throw ContextNotReadyException(
                contextName,
                "namespace '$namespace' has no Deployments/StatefulSets/Jobs to assert readiness on " +
                    "(infra-up did not install the context's workloads, or the wrong namespace resolved)",
            )
        }
        val unmet = checks.filterNot { reader.isReady(namespace, it) }
        if (unmet.isNotEmpty()) {
            throw ContextNotReadyException(
                contextName,
                "namespace '$namespace' not ready: " + unmet.joinToString { "${it.kind}/${it.name}" },
            )
        }
        return ContextHandle(contextName, namespace, reader)
    }
}
