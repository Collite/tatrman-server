package org.tatrman.kantheon.midas.v1

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Stage 1.2 T4 — locks the midas/v1 enum ↔ DDL CHECK-constraint string mapping
 * (contracts.md §6.1). The convention: the DDL string is the proto enum constant
 * name with its type prefix stripped (`CLIENT_ACTIVE` → `'ACTIVE'`,
 * `TX_CASH_DEBIT` → `'CASH_DEBIT'`). V0001's CHECK constraints (Stage 1.3) and the
 * Exposed enum mapping must match these sets exactly; catching drift here keeps
 * the proto, the DDL, and the repository layer in lockstep.
 *
 * proto3 Java enums carry a synthetic `UNRECOGNIZED` constant — excluded.
 */
class MidasEnumDdlMappingSpec :
    StringSpec({

        fun ddlForms(
            names: List<String>,
            prefix: String,
        ): Set<String> = names.filterNot { it == "UNRECOGNIZED" }.map { it.removePrefix(prefix) }.toSet()

        "ClientStatus ↔ clients.status CHECK" {
            ddlForms(ClientStatus.values().map { it.name }, "CLIENT_") shouldBe
                setOf("ACTIVE", "ARCHIVED")
        }

        "PortfolioType ↔ portfolios.portfolio_type CHECK" {
            ddlForms(PortfolioType.values().map { it.name }, "PORTFOLIO_") shouldBe
                setOf("BROKERAGE", "RETIREMENT", "OTHER")
        }

        "PortfolioStatus ↔ portfolios.status CHECK" {
            ddlForms(PortfolioStatus.values().map { it.name }, "PORTFOLIO_") shouldBe
                setOf("ACTIVE", "ARCHIVED")
        }

        "CostBasisMethod ↔ portfolios.cost_basis_method CHECK" {
            ddlForms(CostBasisMethod.values().map { it.name }, "COST_BASIS_") shouldBe
                setOf("FIFO")
        }

        "AssetKind ↔ assets.kind CHECK" {
            ddlForms(AssetKind.values().map { it.name }, "ASSET_") shouldBe
                setOf("STOCK", "ETF", "BOND", "FUND", "CASH")
        }

        "AssetStatus ↔ assets.status CHECK" {
            ddlForms(AssetStatus.values().map { it.name }, "ASSET_") shouldBe
                setOf("ACTIVE", "DELISTED")
        }

        "TransactionKind ↔ transactions.kind CHECK (incl. S2 cash legs)" {
            ddlForms(TransactionKind.values().map { it.name }, "TX_") shouldBe
                setOf(
                    "BUY",
                    "SELL",
                    "DIVIDEND",
                    "INTEREST",
                    "FEE",
                    "TAX",
                    "TRANSFER_IN",
                    "TRANSFER_OUT",
                    "ADJUSTMENT",
                    "CASH_CREDIT",
                    "CASH_DEBIT",
                )
        }

        "TransactionSource ↔ transactions.source CHECK" {
            ddlForms(TransactionSource.values().map { it.name }, "TX_SRC_") shouldBe
                setOf("MANUAL", "LOADER_EXCEL", "LOADER_GOOGLE_FINANCE", "LOADER_API", "DERIVATION", "REVERSAL")
        }

        "ReconcileStatus ↔ reconciliation_decisions.status CHECK" {
            ddlForms(ReconcileStatus.values().map { it.name }, "RECON_") shouldBe
                setOf("OPEN", "EXPECTED", "INVESTIGATE", "RESOLVED")
        }

        "LoaderRunStatus ↔ loader_runs.status CHECK" {
            ddlForms(LoaderRunStatus.values().map { it.name }, "LR_") shouldBe
                setOf("UPLOADED", "PARSING", "MAPPING", "PREVIEW_READY", "COMMITTING", "COMPLETED", "FAILED")
        }
    })
