package org.tatrman.kantheon.ariadne

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.kantheon.ariadne.parse.MetadataModelHandle
import org.tatrman.proteus.v1.Language as TranslatorLanguage
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import shared.translator.orchestrator.ParseResult
import shared.translator.orchestrator.Translator
import java.nio.file.Path

/**
 * WS-T2 T3 — the TPC-DS curated queries parse to a RelNode plan against the
 * `tpcds` model. Reconciles the real `model-ttr` tree, applies the `{name}` → `?`
 * parameter bridge (the rewrite Proteus's orchestrator performs before parse —
 * `TranslatorServiceImpl` §432; the raw `QueryParseWorker` leaves parameterised
 * queries FAILED because Calcite can't parse `{year}`), then parses each via the
 * same [Translator] the service uses.
 *
 * Three of the four shapes parse today — including the **window function**
 * (`customer_running_total`). The fourth, `channel_revenue_cte` (CTE + UNION ALL),
 * is **parked**: the v1 plan wire format has no `Union` op ("RelOp 'LogicalUnion'
 * is not in the v1 wire format"). That fix is authored canonically in
 * **Collite/tatrman** (`ttr-plan-proto` + `ttr-translator`) and reaches kantheon
 * with the ttr-translator Phase B swap (docs/architecture/fork/ttr-translator-extraction.md).
 * The tripwire below flips the day Union support lands — a reminder to promote
 * channel_revenue_cte into the working set.
 */
class TpcdsQueryParseSpec :
    StringSpec({

        val parseableToday =
            setOf(
                "store_sales_by_month",
                "top_items_by_revenue",
                "customer_running_total",
            )
        val parkedPendingUnion = "channel_revenue_cte"

        // The Proteus parameter bridge: `{name}` → positional `?`.
        val paramPlaceholder = Regex("""\{[A-Za-z_][A-Za-z0-9_]*}""")

        fun reconciledModel() =
            run {
                val root: Path =
                    Path
                        .of(checkNotNull(this::class.java.classLoader.getResource("model-ttr/tpcds")).toURI())
                        .parent
                val source =
                    FileBasedSource(
                        sourceId = "model-ttr",
                        priority = 100,
                        storage = LocalFsStorage(id = "model-ttr", rootPath = root),
                    )
                ModelReconciler(ModelDescriptor(id = "test", name = "test", description = "tpcds parse fixture"))
                    .reconcile(listOf(source.load()))
                    .model
            }

        "the join/agg/window curated queries parse to RelNode; the UNION CTE is parked" {
            val model = reconciledModel()
            val translator = Translator(MetadataModelHandle(model))

            val results =
                model.queries.values
                    .filter { it.qname.name in (parseableToday + parkedPendingUnion) }
                    .associate { query ->
                        val bridged = query.sourceText.replace(paramPlaceholder, "?")
                        query.qname.name to translator.parseToRelNode(bridged, TranslatorLanguage.SQL)
                    }

            results.keys shouldBe (parseableToday + parkedPendingUnion)

            // The three non-union shapes — including the window function — parse today.
            parseableToday.forEach { name ->
                withClue("query $name should parse; got: ${results[name]}") {
                    results.getValue(name).shouldBeInstanceOf<ParseResult.Success>()
                }
            }

            // Tripwire: channel_revenue_cte is parked pending plan.v1 `Union` (authored
            // in Collite/tatrman; arrives via the ttr-translator Phase B swap). When it
            // starts parsing, promote it into `parseableToday` above and drop this block.
            withClue(
                "channel_revenue_cte now parses — plan.v1 Union support has landed; " +
                    "promote it into the working set. Result: ${results[parkedPendingUnion]}",
            ) {
                results.getValue(parkedPendingUnion).shouldBeInstanceOf<ParseResult.Failure>()
            }
        }
    })
