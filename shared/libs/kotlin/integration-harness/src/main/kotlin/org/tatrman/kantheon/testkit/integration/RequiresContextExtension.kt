package org.tatrman.kantheon.testkit.integration

import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.listeners.BeforeSpecListener
import io.kotest.core.spec.Spec
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Kotest gate. Before a spec annotated [RequiresContext] it resolves the context
 * (explicit `-Pnamespace` → sysprop `namespace`, else by label), asserts
 * readiness, and stores a [ContextHandle] the spec reads via [contextHandle].
 * **Fail-fast** — it never deploys. Specs without the annotation are ignored, so
 * it is inert for the unit/component tiers.
 *
 * Kotest 6.0 removed classpath scanning (`@AutoScan`), so register it explicitly
 * — apply it on the spec alongside the context annotation:
 *
 * ```
 * @RequiresContext("theseus-runquery")
 * @ApplyExtension(RequiresContextExtension::class)
 * class RunQueryIntegrationSpec : StringSpec({ ... })
 * ```
 *
 * (`@ApplyExtension` instantiates it via the no-arg constructor.)
 */
class RequiresContextExtension internal constructor(
    private val readerFactory: () -> ClusterReader,
) : BeforeSpecListener,
    AfterSpecListener {
    constructor() : this({ Fabric8ClusterReader() })

    override suspend fun beforeSpec(spec: Spec) {
        // Java reflection (the annotation is @Retention(RUNTIME)) — avoids a
        // kotlin-reflect dependency for a single annotation lookup.
        val annotation = spec.javaClass.getAnnotation(RequiresContext::class.java) ?: return
        val namespace = System.getProperty("namespace")?.takeIf { it.isNotBlank() }
        val handle = ContextGate(readerFactory()).open(annotation.name, namespace)
        ContextRegistry.put(spec::class, handle)
    }

    /** Release the handle's resources (port-forwards / kube client) when the spec ends. */
    override suspend fun afterSpec(spec: Spec) {
        ContextRegistry.remove(spec::class)?.close()
    }
}

/** Per-spec store of the resolved [ContextHandle], populated by [RequiresContextExtension]. */
object ContextRegistry {
    private val handles = ConcurrentHashMap<KClass<out Spec>, ContextHandle>()

    fun put(
        spec: KClass<out Spec>,
        handle: ContextHandle,
    ) {
        handles[spec] = handle
    }

    fun get(spec: KClass<out Spec>): ContextHandle? = handles[spec]

    /** Remove and return the handle for [spec] (so the gate can close it in afterSpec). */
    fun remove(spec: KClass<out Spec>): ContextHandle? = handles.remove(spec)
}

/** The live [ContextHandle] for this spec; available after the gate has run. */
fun Spec.contextHandle(): ContextHandle =
    ContextRegistry.get(this::class)
        ?: error(
            "No ContextHandle for ${this::class.simpleName} — annotate the spec with " +
                "@RequiresContext and ensure the gate (RequiresContextExtension) ran.",
        )
