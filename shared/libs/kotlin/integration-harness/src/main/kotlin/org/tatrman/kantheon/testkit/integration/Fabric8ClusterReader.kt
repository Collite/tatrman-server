package org.tatrman.kantheon.testkit.integration

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.LocalPortForward
import java.util.concurrent.ConcurrentHashMap

/**
 * fabric8-backed [ClusterReader] — READ-ONLY w.r.t. cluster state. Uses only
 * `get`/`list` for status; the one non-read action is opening **port-forwards**
 * for [serviceBaseUrl] (a tunnel, not a mutation — no apply/delete/patch). All
 * bring-up/teardown still lives in olymp `infra-up/down` (architecture §3).
 *
 * Readiness is **derived** from what the namespace actually runs — every
 * Deployment, StatefulSet, and Job (the kinds olymp's `infra-up` itself gates on,
 * test-harness.md §5). kantheon never parses olymp's context manifest and reads no
 * handshake annotation; the cross-repo contract is the two ns labels only
 * (contracts §6).
 *
 * **Out-of-cluster access.** The suite runs on the CI runner, not in a pod, so the
 * in-cluster service DNS (`<svc>.<ns>.svc`) is unresolvable. [serviceBaseUrl]
 * therefore tunnels through the kube API server (which the runner *can* reach) via
 * a port-forward and returns a `http://localhost:<localPort>` URL. Forwards are
 * cached per service and released on [close] (the gate closes the handle in
 * `afterSpec`).
 */
class Fabric8ClusterReader(
    private val client: KubernetesClient = KubernetesClientBuilder().build(),
) : ClusterReader,
    AutoCloseable {
    private val forwards = ConcurrentHashMap<String, LocalPortForward>()

    override fun resolveNamespace(contextName: String): String? =
        client
            .namespaces()
            .withLabel(CONTEXT_LABEL, contextName)
            .list()
            .items
            .firstOrNull()
            ?.metadata
            ?.name

    override fun readinessChecks(namespace: String): List<ReadinessCheck> {
        val deployments =
            client
                .apps()
                .deployments()
                .inNamespace(namespace)
                .list()
                .items
                .mapNotNull { it.metadata?.name }
                .map { ReadinessCheck(ReadinessCheck.Kind.DEPLOYMENT, it) }
        val statefulSets =
            client
                .apps()
                .statefulSets()
                .inNamespace(namespace)
                .list()
                .items
                .mapNotNull { it.metadata?.name }
                .map { ReadinessCheck(ReadinessCheck.Kind.STATEFULSET, it) }
        val jobs =
            client
                .batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .list()
                .items
                .mapNotNull { it.metadata?.name }
                .map { ReadinessCheck(ReadinessCheck.Kind.JOB, it) }
        return deployments + statefulSets + jobs
    }

    override fun isReady(
        namespace: String,
        check: ReadinessCheck,
    ): Boolean =
        when (check.kind) {
            ReadinessCheck.Kind.DEPLOYMENT -> {
                val dep =
                    client
                        .apps()
                        .deployments()
                        .inNamespace(namespace)
                        .withName(check.name)
                        .get()
                dep?.status?.conditions?.any { it.type == "Available" && it.status == "True" } == true
            }
            ReadinessCheck.Kind.STATEFULSET -> {
                val sts =
                    client
                        .apps()
                        .statefulSets()
                        .inNamespace(namespace)
                        .withName(check.name)
                        .get()
                val desired = sts?.spec?.replicas ?: 0
                desired > 0 && sts?.status?.readyReplicas == desired
            }
            ReadinessCheck.Kind.JOB -> {
                val job =
                    client
                        .batch()
                        .v1()
                        .jobs()
                        .inNamespace(namespace)
                        .withName(check.name)
                        .get()
                job?.status?.conditions?.any { it.type == "Complete" && it.status == "True" } == true
            }
        }

    override fun serviceBaseUrl(
        namespace: String,
        service: String,
    ): String? {
        val svc =
            client
                .services()
                .inNamespace(namespace)
                .withName(service)
                .get() ?: return null
        val remotePort =
            svc.spec
                ?.ports
                ?.firstOrNull()
                ?.port ?: return null
        // Tunnel through the API server (the runner is out-of-cluster). One forward per service,
        // reused across the spec's tool calls; released in close().
        val lpf =
            forwards.computeIfAbsent("$namespace/$service") {
                client
                    .services()
                    .inNamespace(namespace)
                    .withName(service)
                    .portForward(remotePort)
            }
        return "http://localhost:${lpf.localPort}"
    }

    /** Release every open port-forward and the underlying client. Idempotent. */
    override fun close() {
        forwards.values.forEach { runCatching { it.close() } }
        forwards.clear()
        runCatching { client.close() }
    }

    companion object {
        const val CONTEXT_LABEL = "olymp.collite/context"
    }
}
