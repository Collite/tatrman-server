package org.tatrman.kantheon.testkit.integration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

/**
 * Stage 2.1 T2 — the Kotest listener wiring (annotation read → gate → registry),
 * exercised directly with a fake reader factory (no cluster, no Kotest runtime).
 * The annotated specs are *local* classes so Kotest never auto-discovers and runs
 * them with the real fabric8 reader.
 */
class RequiresContextExtensionSpec :
    StringSpec({
        "resolves and stores a ContextHandle for an annotated spec" {
            @RequiresContext("unit-ctx")
            class AnnotatedFake : StringSpec()

            val reader = mockk<ClusterReader>()
            every { reader.resolveNamespace("unit-ctx") } returns "ns-x"
            every { reader.readinessChecks("ns-x") } returns
                listOf(ReadinessCheck(ReadinessCheck.Kind.DEPLOYMENT, "d"))
            every { reader.isReady("ns-x", any()) } returns true

            val fake = AnnotatedFake()
            runBlocking { RequiresContextExtension { reader }.beforeSpec(fake) }

            ContextRegistry.get(AnnotatedFake::class)!!.namespace shouldBe "ns-x"
        }

        "ignores a spec with no @RequiresContext (does not touch the cluster)" {
            class PlainFake : StringSpec()

            // A reader that would blow up if used — proves the listener never calls it.
            val reader = mockk<ClusterReader>()
            val plain = PlainFake()
            runBlocking { RequiresContextExtension { reader }.beforeSpec(plain) }

            ContextRegistry.get(PlainFake::class).shouldBeNull()
        }
    })
