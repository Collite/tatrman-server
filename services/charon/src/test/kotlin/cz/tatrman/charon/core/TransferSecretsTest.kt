package cz.tatrman.charon.core

import cz.tatrman.secrets.spi.SecretMaterial
import cz.tatrman.secrets.spi.SecretRef
import cz.tatrman.secrets.spi.SecretResolutionException
import cz.tatrman.secrets.spi.SecretStore
import cz.tatrman.secrets.spi.SecretStoreRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * PL-P3.S1.T4 — per-transfer secret resolution for the **source×target** pair (contracts §17, H-5),
 * extending the S6 canary to Charon's path. A pg→files transfer resolves exactly two refs, injects both
 * `TTR_CONN_*`, and the planted canary proves never-at-rest: it reaches the transfer env (the test is
 * sensitive — the secret is real) yet the [SecretMaterial] is zeroized and never renders in a log/artifact.
 *
 * This pins the §17 resolution CORE (the [TransferSecretInjector]); the live-k8s FileSecretStore mount +
 * the gRPC per-request wiring ride T5 / the operate layer — exactly as S6's CanaryTest scoped the hall.
 */
class TransferSecretsTest :
    StringSpec({

        val canary = "canary-9f81deadbeef"

        // A k8s-scheme store that plants the canary for every ref and records what it was asked to resolve
        // + every material it issued (so we can assert least-exposure + zeroization after injection).
        class CanaryStore : SecretStore {
            override val scheme = "k8s"
            val resolvedPaths = mutableListOf<String>()
            val issued = mutableListOf<SecretMaterial>()

            override fun resolve(ref: SecretRef): SecretMaterial {
                resolvedPaths += ref.path
                return SecretMaterial(canary.toByteArray()).also { issued += it }
            }
        }

        // A pg→files transfer's declared connections: the source (pg) + the target (files) pair.
        val pair =
            linkedMapOf(
                "TTR_CONN_ERP_PG" to SecretRef.parse("secret://k8s/erp-pg"),
                "TTR_CONN_FILES" to SecretRef.parse("secret://k8s/files-staging"),
            )

        "a pg→files transfer resolves exactly the two declared refs and injects both TTR_CONN_*" {
            val store = CanaryStore()
            val injector = TransferSecretInjector(SecretStoreRegistry(listOf(store)))

            val env = injector.inject(pair)

            // exactly two refs resolved — the source×target pair, nothing more (H-5 least exposure).
            store.resolvedPaths shouldContainExactly listOf("erp-pg", "files-staging")
            env.keys.toList() shouldContainExactly listOf("TTR_CONN_ERP_PG", "TTR_CONN_FILES")
            // sensitivity: the real secret DID reach the transfer env under both TTR_CONN_* names.
            env["TTR_CONN_ERP_PG"] shouldBe canary
            env["TTR_CONN_FILES"] shouldBe canary
        }

        "only the declared connections are injected — an undeclared connection is never resolved" {
            val store = CanaryStore()
            val injector = TransferSecretInjector(SecretStoreRegistry(listOf(store)))

            // The transfer declares ONLY the pg source; the files ref exists in the world but isn't part of
            // THIS transfer, so it must never be touched.
            injector.inject(linkedMapOf("TTR_CONN_ERP_PG" to SecretRef.parse("secret://k8s/erp-pg")))

            store.resolvedPaths shouldContainExactly listOf("erp-pg")
        }

        "the resolved material is zeroized after injection — nothing sits at rest" {
            val store = CanaryStore()
            val injector = TransferSecretInjector(SecretStoreRegistry(listOf(store)))

            injector.inject(pair)

            store.issued.size shouldBe 2
            store.issued.forEach { material ->
                // asString() on a zeroized material throws — proof the buffer was cleared post-injection.
                shouldThrow<IllegalStateException> { material.asString() }
                material.toString() shouldBe "SecretMaterial(REDACTED)"
            }
        }

        "the canary never renders into a log line or artifact (H-5): material + handle both redact" {
            val store = CanaryStore()
            val injector = TransferSecretInjector(SecretStoreRegistry(listOf(store)))
            injector.inject(pair)

            // The SecretMaterial's own toString is redacted (a stray log of the material can't leak it).
            store.issued.first().toString() shouldNotContain canary
            store.issued.first().toString() shouldContain "REDACTED"

            // A ConnectionHandle built from a resolved DSN redacts credentials in toString (never logs the pw).
            val handle =
                ConnectionHandle(
                    id = "erp-pg",
                    dialect = DbDialect.POSTGRES,
                    jdbcUrl = "jdbc:postgresql://db:5432/erp",
                    username = "svc",
                    password = canary,
                    allow = AllowList(read = true, write = false, schemas = setOf("public")),
                    poolMax = 2,
                )
            handle.toString() shouldNotContain canary
        }

        "an unreachable secret store fails pre-flight with PLT-SEC-001, before any I/O" {
            val down =
                object : SecretStore {
                    override val scheme = "k8s"

                    override fun resolve(ref: SecretRef): SecretMaterial =
                        throw SecretResolutionException("store unreachable at resolution")
                }
            val injector = TransferSecretInjector(SecretStoreRegistry(listOf(down)))

            val ex = shouldThrow<SecretPreflightException> { injector.inject(pair) }
            ex.code shouldBe "PLT-SEC-001"
            ex.connection shouldBe "TTR_CONN_ERP_PG"
        }
    })
