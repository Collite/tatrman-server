package org.tatrman.kantheon.sysifos.v1

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Stage 1.1 T6 — `sysifos/v1` shape guard. Proves the relocated package compiles
 * to Kotlin with the four Sysifos-arc additions and that the stream/batch types
 * round-trip on the wire.
 *
 * NB: proto3 has no field defaults; `track_cash` default-true is applied in the
 * BFF mapping (Stage 2.1) and the Zod schema, not the proto. The contract
 * documents intent — this spec asserts the field exists and carries values.
 */
class SysifosProtoSpec :
    StringSpec({
        "PortfolioForm carries name + track_cash (default applied BFF-side, not in proto3)" {
            val f =
                PortfolioForm
                    .newBuilder()
                    .setName("X")
                    .setTrackCash(true)
                    .build()
            f.name shouldBe "X"
            f.trackCash shouldBe true
        }
        "TransactionBatchForm carries rows + skip_existing" {
            val b =
                TransactionBatchForm
                    .newBuilder()
                    .setPortfolioId("p")
                    .addRows(TransactionForm.getDefaultInstance())
                    .setSkipExisting(true)
                    .build()
            b.rowsCount shouldBe 1
            b.skipExisting shouldBe true
        }
        "SysifosStreamEvent round-trips a BatchRowResult" {
            val e =
                SysifosStreamEvent
                    .newBuilder()
                    .setBatchRowResult(
                        BatchRowResult
                            .newBuilder()
                            .setRowIndex(3)
                            .setOutcome(BatchRowOutcome.BR_COMMITTED)
                            .build(),
                    ).build()
            SysifosStreamEvent.parseFrom(e.toByteArray()).batchRowResult.rowIndex shouldBe 3
        }
        "DraftKind + DraftStatus carry the Sysifos-arc additions" {
            DraftKind.DRAFT_TRANSACTION_BATCH.number shouldBe 6
            DraftKind.DRAFT_ASSET.number shouldBe 7
            DraftStatus.DRAFT_COMMITTING.number shouldBe 4
        }
        "AssetForm + ReconciliationDecisionForm compile with midas/v1 enums" {
            val a =
                AssetForm
                    .newBuilder()
                    .setSymbol(
                        "AAPL",
                    ).setKind(org.tatrman.kantheon.midas.v1.AssetKind.ASSET_STOCK)
                    .build()
            a.symbol shouldBe "AAPL"
            val r =
                ReconciliationDecisionForm
                    .newBuilder()
                    .setDiffKey("k")
                    .setStatus(org.tatrman.kantheon.midas.v1.ReconcileStatus.RECON_RESOLVED)
                    .build()
            r.diffKey shouldBe "k"
        }
    })
