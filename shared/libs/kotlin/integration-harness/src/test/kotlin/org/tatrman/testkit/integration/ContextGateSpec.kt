package org.tatrman.testkit.integration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/**
 * Stage 2.1 T2 — the readiness gate logic, unit-tested against a mocked
 * [ClusterReader] (no cluster). Covers the three required cases (ready / no
 * namespace / not ready) plus the explicit-namespace short-circuit. The reader
 * port is read-only by construction (no write methods exist to call), which is
 * how "the kantheon side never mutates the cluster" is guaranteed.
 */
class ContextGateSpec :
    StringSpec({
        val ctx = "theseus-runquery"
        val dep = ReadinessCheck(ReadinessCheck.Kind.DEPLOYMENT, "theseus")

        "opens a handle when the namespace resolves and all checks are ready" {
            val reader = mockk<ClusterReader>()
            every { reader.resolveNamespace(ctx) } returns "ns-1"
            every { reader.readinessChecks("ns-1") } returns listOf(dep)
            every { reader.isReady("ns-1", dep) } returns true

            val handle = ContextGate(reader).open(ctx)

            handle.context shouldBe ctx
            handle.namespace shouldBe "ns-1"
        }

        "fails fast when no namespace resolves and none is given" {
            val reader = mockk<ClusterReader>()
            every { reader.resolveNamespace(ctx) } returns null

            val ex = shouldThrow<ContextNotReadyException> { ContextGate(reader).open(ctx) }
            ex.contextName shouldBe ctx
        }

        "fails fast when a readiness check is unmet" {
            val reader = mockk<ClusterReader>()
            every { reader.resolveNamespace(ctx) } returns "ns-1"
            every { reader.readinessChecks("ns-1") } returns listOf(dep)
            every { reader.isReady("ns-1", dep) } returns false

            val ex = shouldThrow<ContextNotReadyException> { ContextGate(reader).open(ctx) }
            ex.message!! shouldContain "DEPLOYMENT/theseus"
        }

        "fails fast when the namespace declares no readiness checks" {
            val reader = mockk<ClusterReader>()
            every { reader.resolveNamespace(ctx) } returns "ns-1"
            every { reader.readinessChecks("ns-1") } returns emptyList()

            shouldThrow<ContextNotReadyException> { ContextGate(reader).open(ctx) }
        }

        "uses the explicit namespace and never resolves by label" {
            val reader = mockk<ClusterReader>()
            every { reader.readinessChecks("explicit-ns") } returns listOf(dep)
            every { reader.isReady("explicit-ns", dep) } returns true

            val handle = ContextGate(reader).open(ctx, explicitNamespace = "explicit-ns")

            handle.namespace shouldBe "explicit-ns"
            verify(exactly = 0) { reader.resolveNamespace(any()) }
        }
    })
