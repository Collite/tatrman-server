package org.tatrman.kantheon.proteus

import org.tatrman.plan.v1.SchemaCode
import org.tatrman.proteus.v1.Language
import org.tatrman.proteus.v1.SqlDialect
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.kantheon.proteus.model.BootFixtureModel
import shared.translator.orchestrator.ParseResult
import shared.translator.orchestrator.Translator
import shared.translator.orchestrator.UnparseResult

/**
 * Guards the startup Calcite warm-up: [WARMUP_SQL] must actually parse against the
 * boot fixture and unparse to MSSQL. If it ever silently fails (a fixture change,
 * a typo), the warm-up no-ops and the first real query pays the ~40-50s cold-start
 * again — so we assert the full parse→unparse path the warm-up exercises.
 */
class WarmUpSpec :
    StringSpec({
        "WARMUP_SQL parses against the boot fixture and unparses to MSSQL" {
            val translator = Translator(BootFixtureModel.handle())

            val parsed =
                translator.parseToRelNode(
                    WARMUP_SQL,
                    Language.SQL,
                    SchemaCode.DB,
                    sourceSchema = SchemaCode.DB,
                )
            val success = parsed.shouldBeInstanceOf<ParseResult.Success>()

            val unparsed =
                translator.unparseFromRelNode(
                    success.plan,
                    Language.SQL,
                    SqlDialect.MSSQL,
                    optimize = true,
                )
            unparsed.shouldBeInstanceOf<UnparseResult.Success>()
        }
    })
