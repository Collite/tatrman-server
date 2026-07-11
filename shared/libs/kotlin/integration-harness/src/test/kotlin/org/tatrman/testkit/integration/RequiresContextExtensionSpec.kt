package org.tatrman.testkit.integration

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

        // WS-R1 — context selection: a `integrationTest -Pcontext=X` run fans out over every
        // module's specs, but only the spec whose @RequiresContext == X may bind to the (single)
        // live namespace. A non-selected spec is skipped without touching the cluster.
        "skips a spec whose @RequiresContext differs from the selected -Pcontext" {
            @RequiresContext("golem-erp")
            class OtherCtxFake : StringSpec()

            // A reader that would blow up if used — proves the mismatched context never touches it.
            val reader = mockk<ClusterReader>()
            System.setProperty("context", "query-runquery")
            try {
                val fake = OtherCtxFake()
                runBlocking { RequiresContextExtension { reader }.beforeSpec(fake) }
                ContextRegistry.get(OtherCtxFake::class).shouldBeNull()
            } finally {
                System.clearProperty("context")
            }
        }

        "runs the gate when -Pcontext matches the spec's @RequiresContext" {
            @RequiresContext("query-runquery")
            class MatchCtxFake : StringSpec()

            val reader = mockk<ClusterReader>()
            every { reader.resolveNamespace("query-runquery") } returns "ns-y"
            every { reader.readinessChecks("ns-y") } returns
                listOf(ReadinessCheck(ReadinessCheck.Kind.DEPLOYMENT, "d"))
            every { reader.isReady("ns-y", any()) } returns true

            System.setProperty("context", "query-runquery")
            try {
                val fake = MatchCtxFake()
                runBlocking { RequiresContextExtension { reader }.beforeSpec(fake) }
                ContextRegistry.get(MatchCtxFake::class)!!.namespace shouldBe "ns-y"
            } finally {
                System.clearProperty("context")
            }
        }
    })
