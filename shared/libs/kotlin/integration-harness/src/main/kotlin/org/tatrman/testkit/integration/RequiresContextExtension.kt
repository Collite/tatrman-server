// SPDX-License-Identifier: Apache-2.0
package org.tatrman.testkit.integration

import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.listeners.BeforeSpecListener
import io.kotest.core.spec.Spec
import io.kotest.core.test.TestCase
import io.kotest.engine.test.TestResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Kotest gate. Before a spec annotated [RequiresContext] it resolves the context
 * (explicit `-Pnamespace` → sysprop `namespace`, else by label), asserts
 * readiness, and stores a [ContextHandle] the spec reads via [contextHandle].
 * **Fail-fast** — it never deploys. Specs without the annotation are ignored, so
 * it is inert for the unit/component tiers.
 *
 * **Context selection (WS-R1).** A `./gradlew integrationTest` run fans out across
 * *every* module's `integrationTest` task, so all `@RequiresContext` specs are on
 * the run — but only **one** context is up per run (one namespace, one run-id;
 * olymp test-harness §8). When `-Pcontext` (sysprop `context`) names a context,
 * specs whose `@RequiresContext` name **differs** are skipped wholesale: `beforeSpec`
 * doesn't touch the cluster and every test is [TestResult.Ignored]. Without a
 * `context` sysprop the filter is inert (every annotated spec runs — the direct
 * unit-test path). This is what lets `just it-bp-dsk <context>` run the one selected
 * spec without the others failing against the wrong namespace.
 *
 * Kotest 6.0 removed classpath scanning (`@AutoScan`), so register it explicitly
 * — apply it on the spec alongside the context annotation:
 *
 * ```
 * @RequiresContext("query-runquery")
 * @ApplyExtension(RequiresContextExtension::class)
 * class RunQueryIntegrationSpec : StringSpec({ ... })
 * ```
 *
 * (`@ApplyExtension` instantiates it via the no-arg constructor.)
 */
class RequiresContextExtension internal constructor(
    private val readerFactory: () -> ClusterReader,
) : BeforeSpecListener,
    AfterSpecListener,
    TestCaseExtension {
    constructor() : this({ Fabric8ClusterReader() })

    override suspend fun beforeSpec(spec: Spec) {
        // Java reflection (the annotation is @Retention(RUNTIME)) — avoids a
        // kotlin-reflect dependency for a single annotation lookup.
        val annotation = spec.javaClass.getAnnotation(RequiresContext::class.java) ?: return
        // A different context is under test — don't open a handle or touch the cluster.
        if (!contextSelected(annotation.name)) return
        val namespace = System.getProperty("namespace")?.takeIf { it.isNotBlank() }
        val handle = ContextGate(readerFactory()).open(annotation.name, namespace)
        ContextRegistry.put(spec::class, handle)
    }

    /** Skip every test of an annotated spec whose context is not the one selected by `-Pcontext`. */
    override suspend fun intercept(
        testCase: TestCase,
        execute: suspend (TestCase) -> TestResult,
    ): TestResult {
        val annotation = testCase.spec.javaClass.getAnnotation(RequiresContext::class.java)
        if (annotation != null && !contextSelected(annotation.name)) {
            return TestResult.Ignored(
                "integration context '${System.getProperty("context")}' selected; " +
                    "spec requires '${annotation.name}'",
            )
        }
        return execute(testCase)
    }

    /** Release the handle's resources (port-forwards / kube client) when the spec ends. */
    override suspend fun afterSpec(spec: Spec) {
        ContextRegistry.remove(spec::class)?.close()
    }

    /**
     * True when [name] is the context under test — i.e. `-Pcontext` (sysprop `context`) is unset
     * (no filter; the direct unit-test path) or equals [name]. When it names a *different* context,
     * the spec is skipped so it never binds to another context's namespace.
     */
    private fun contextSelected(name: String): Boolean {
        val requested = System.getProperty("context")?.takeIf { it.isNotBlank() } ?: return true
        return requested == name
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
