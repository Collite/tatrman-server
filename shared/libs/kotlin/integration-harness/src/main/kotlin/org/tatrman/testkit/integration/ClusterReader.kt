package org.tatrman.testkit.integration

/** A single workload the gate re-asserts is Ready before a suite runs. */
data class ReadinessCheck(
    val kind: Kind,
    val name: String,
) {
    enum class Kind { DEPLOYMENT, STATEFULSET, JOB }
}

/**
 * READ-ONLY view of the test cluster used by the readiness gate. Every method is
 * a status read — there is intentionally **no** apply/delete/patch on this port,
 * so "the kantheon side never mutates the cluster" holds by construction (testing
 * architecture §3). The fabric8-backed implementation is [Fabric8ClusterReader].
 */
interface ClusterReader {
    /** Namespace labelled `olymp.collite/context=<contextName>`, or null if none. */
    fun resolveNamespace(contextName: String): String?

    /**
     * The workloads in the namespace to re-assert Ready — **derived** from what is
     * actually deployed (every Deployment / StatefulSet / Job), not read from a
     * handshake annotation. The cross-repo contract is the two ns labels only
     * (testing contracts §6); olymp already blocks on its own `readiness[]` before
     * printing the namespace, so this gate is a defense-in-depth re-assertion that
     * needs no extra surface from olymp. Empty means the namespace has no workloads.
     */
    fun readinessChecks(namespace: String): List<ReadinessCheck>

    /** Whether [check] is satisfied now (Deployment Available / Endpoints have ready addresses). */
    fun isReady(
        namespace: String,
        check: ReadinessCheck,
    ): Boolean

    /** In-cluster base URL for [service] (`http://<service>.<namespace>.svc:<port>`), or null. */
    fun serviceBaseUrl(
        namespace: String,
        service: String,
    ): String?
}

/** Thrown by the gate when a context is absent or not Ready — fail fast, never self-deploy. */
class ContextNotReadyException(
    val contextName: String,
    detail: String,
) : RuntimeException("Integration context '$contextName' is not ready: $detail")
