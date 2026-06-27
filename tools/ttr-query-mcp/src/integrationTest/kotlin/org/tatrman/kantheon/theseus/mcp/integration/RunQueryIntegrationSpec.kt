package org.tatrman.kantheon.theseus.mcp.integration

import io.kotest.core.annotation.Tags
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.tatrman.kantheon.testkit.integration.RequiresContext
import org.tatrman.kantheon.testkit.integration.RequiresContextExtension
import org.tatrman.kantheon.testkit.integration.contextHandle

/**
 * Stage 2.2 — end-to-end `run_query` (MCP tool `query`) through the **real**
 * forked chain: theseus-mcp → Theseus → Proteus → Argos → Kyklop → Brontes →
 * MSSQL. Gated by `@RequiresContext("theseus-runquery")` — compiles + skips until
 * olymp stands the context up (Stage 2.3), then runs green.
 *
 * **No WireMock stubs are needed for this path** (corrected from the original
 * plan): the `query` tool never enables Argos's LLM-guard, and modeler/TTR is not
 * a runtime call — so there is no external HTTP dependency to stub. The only true
 * external is MSSQL, which is the context's `mssql` platform member + seed. The
 * Stage 2.1 in-cluster WireMock loader stays available for any future scenario
 * that turns LLM-guard on.
 *
 * ## Context requirements (T6 — reconciled with olymp `test-contexts/theseus-runquery/` as wired in Stage 2.3)
 *  - Services (real): theseus-mcp, theseus, proteus, argos, kyklop, brontes. **Ariadne is NOT
 *    deployed** — Argos runs its fixture model (`ARGOS_USE_FIXTURE_MODEL=true`); the raw-SQL
 *    `query` path resolves no metadata through Ariadne.
 *  - Platform: `mssql` (the base `mssql-init` Job seeds the sample dataset) + `wiremock` (empty;
 *    unused on this path). The suite expects `dbo.sample_orders` with 4 rows incl. `tenant_id`
 *    `t-alpha` — **coordinate the olymp mssql seed with this shape (Stage 2.3 T5 live check).**
 *  - Identity: `theseus-mcp` runs with `requireIdentity=true` (the chart default) — this is what
 *    the one ACTIVE assertion (missing-bearer fail-closed) proves end-to-end.
 *  - `readiness`: the kantheon gate **derives** readiness from the namespace (every Deployment
 *    Available + the `mssql-init` Job Complete) — it reads no handshake annotation; the only
 *    cross-repo surface is the `olymp.collite/context`/`run` ns labels (contracts §6).
 *
 * **Scoped close (Stage 2.3).** The query *result* + RLS assertions are disabled behind
 * `modelAlignedContext` (see the body) until the context's Model matches its seed: Proteus' fixture
 * model has no `dbo.sample_orders` (→ `detection_failed`), and the deployed Argos policy is
 * `tenant_isolation` (row-level), not the column-DENY the RLS case assumed. Re-enabling is the
 * Phase 3 follow-up (plan.md): an Ariadne model — or a fixture model — aligned with the seed.
 */
@RequiresContext("theseus-runquery")
@ApplyExtension(RequiresContextExtension::class)
@Tags("integration")
class RunQueryIntegrationSpec :
    StringSpec({

        // ── Scoped close (testing arc Stage 2.3, Bora 2026-06-20) ────────────────────────────
        // The harness is proven (bring-up → gate → teardown, fail-fast readiness, nightly) and
        // the identity-discipline assertion below is GREEN end-to-end. The query *result* + RLS
        // assertions are gated OFF until the context's Model matches its seed: the live first run
        // showed `detection_failed` (Proteus' fixture model has no `dbo.sample_orders`, so SQL
        // schema auto-detection can't classify the query — and the `query` tool exposes no schema
        // hint), and the deployed Argos fixture policy is `tenant_isolation` (row-level on
        // tenant_id), NOT the column-DENY the RLS case assumes. Re-enable by flipping the flag
        // once theseus-runquery gains a model aligned with the mssql-init seed (Ariadne model, or
        // a fixture model containing dbo.sample_orders) + an aligned Argos policy/bearer identity.
        // Tracking: docs/implementation/v1/testing/plan.md Phase 3 + tasks-p2-s2.3 DONE criteria.
        val modelAlignedContext = false

        // T1 — happy path: real rows from real MSSQL, real columns from Proteus translation.
        "query returns the seeded rows from real MSSQL with the expected columns"
            .config(enabled = modelAlignedContext) {
                val handle = contextHandle()
                val bearer = unsignedJwt("alice", roles = listOf("analyst"))

                val res =
                    runBlocking {
                        handle.callQuery(
                            sqlQueryArgs("SELECT id, tenant_id, region, amount FROM dbo.sample_orders ORDER BY id"),
                            bearer,
                        )
                    }

                res.isError shouldBe false
                res.ok() shouldBe true
                res.rowCount() shouldBe 4
                res.columnNames() shouldContainAll listOf("id", "tenant_id", "region", "amount")
                // A real seeded value proves the chain reached MSSQL and didn't short-circuit
                // (the substitute for a WireMock request-journal check, which is N/A here — T5).
                res.bodyText() shouldContain "t-alpha"
            }

        // T3 — OBO/bearer discipline: missing identity fails closed at the theseus-mcp edge.
        // (Token `exp` is enforced at ingress, not in-service in v1 — see kantheon-security.md.)
        "a missing OBO bearer fails closed with missing_user_identity" {
            val handle = contextHandle()

            val res = runBlocking { handle.callQuery(sqlQueryArgs("SELECT id FROM dbo.sample_orders"), bearer = null) }

            res.isError shouldBe true
            res.ok() shouldBe false
            res.firstMessageCode() shouldBe "missing_user_identity"
        }

        // T4 — RLS negative path. NOTE: assumes a column-DENY Argos policy; the deployed fixture
        // is `tenant_isolation` (row-level), so this needs realignment when re-enabled (see flag).
        "a role denied a column gets a column_denied error envelope (no leaked data)"
            .config(enabled = modelAlignedContext) {
                val handle = contextHandle()
                val restricted = unsignedJwt("bob", roles = listOf("restricted"))

                val res =
                    runBlocking {
                        handle.callQuery(
                            sqlQueryArgs("SELECT id, amount FROM dbo.sample_orders"),
                            restricted,
                        )
                    }

                res.isError shouldBe true
                res.firstMessageCode() shouldBe "column_denied"
            }

        // T4 — the permitted-role variant of the same query returns rows.
        "a permitted role gets rows for the same column query"
            .config(enabled = modelAlignedContext) {
                val handle = contextHandle()
                val analyst = unsignedJwt("alice", roles = listOf("analyst"))

                val res =
                    runBlocking { handle.callQuery(sqlQueryArgs("SELECT id, amount FROM dbo.sample_orders"), analyst) }

                res.isError shouldBe false
                (res.rowCount() ?: 0) shouldBeGreaterThan 0
            }
    })
