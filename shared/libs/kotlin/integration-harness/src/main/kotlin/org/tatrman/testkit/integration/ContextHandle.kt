package org.tatrman.testkit.integration

/**
 * Handle to a live integration context, injected into a `@RequiresContext` spec
 * (read via [contextHandle]). Resolves reachable service URLs lazily through the
 * [ClusterReader] (a `localhost` port-forward when out-of-cluster). [close]
 * releases any resources the reader holds (port-forwards, the kube client); the
 * gate calls it in `afterSpec`.
 */
class ContextHandle(
    val context: String,
    val namespace: String,
    private val reader: ClusterReader,
) : AutoCloseable {
    /** Reachable base URL for [service]; throws if the Service isn't resolvable. */
    fun url(service: String): String =
        reader.serviceBaseUrl(namespace, service)
            ?: error("No Service '$service' resolvable in namespace '$namespace' for context '$context'.")

    /** Base URL of the context's WireMock instance — feed to `WireMockAdmin`. */
    val wireMockAdmin: String get() = url("wiremock")

    /** Release reader-held resources (port-forwards / kube client) if it is [AutoCloseable]. */
    override fun close() {
        (reader as? AutoCloseable)?.close()
    }
}
