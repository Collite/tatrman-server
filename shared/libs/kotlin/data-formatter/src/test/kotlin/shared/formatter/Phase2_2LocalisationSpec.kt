package shared.formatter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import shared.formatter.core.ColumnDecoration
import shared.formatter.core.FormatOptions
import shared.formatter.core.LocalizedString
import shared.formatter.core.OutputFormat

/**
 * Phase 2.2 — verifies the side-channel decorator path:
 *  * `FormatOptions.columnMetadata` overlays display labels + value labels onto
 *    columns produced by the JSON-rows reader.
 *  * Per-writer behaviour: markdown / csv / tsv localise the header and
 *    substitute cell values; JSON keeps raw keys but localises cell values
 *    and (when requested) emits a `__columnLabels` side-car.
 */
@Suppress("ClassName")
class Phase2_2LocalisationSpec :
    StringSpec({

        val sampleJson =
            """
            [
              {"id": 1, "stav": 1},
              {"id": 2, "stav": 2}
            ]
            """.trimIndent().toByteArray(Charsets.UTF_8)

        val decorations =
            mapOf(
                "stav" to
                    ColumnDecoration(
                        displayLabel =
                            LocalizedString(mapOf("cs" to "Stav", "en" to "Status")),
                        valueLabels =
                            mapOf(
                                "1" to LocalizedString(mapOf("cs" to "Aktivní", "en" to "Active")),
                                "2" to LocalizedString(mapOf("cs" to "Neaktivní", "en" to "Inactive")),
                            ),
                    ),
            )

        // ----- Markdown -----

        "markdown header uses displayLabel for cs language" {
            val r =
                DataFormatter.fromJsonRows(
                    sampleJson,
                    OutputFormat.MARKDOWN,
                    FormatOptions(columnMetadata = decorations, preferredLanguage = "cs"),
                )
            r.bytesUtf8() shouldContain "| id | Stav |"
        }

        "markdown header falls back to en when requested language is unknown" {
            val r =
                DataFormatter.fromJsonRows(
                    sampleJson,
                    OutputFormat.MARKDOWN,
                    FormatOptions(columnMetadata = decorations, preferredLanguage = "de"),
                )
            r.bytesUtf8() shouldContain "| id | Status |"
        }

        "markdown cell values substitute via valueLabels (cs)" {
            val r =
                DataFormatter.fromJsonRows(
                    sampleJson,
                    OutputFormat.MARKDOWN,
                    FormatOptions(columnMetadata = decorations, preferredLanguage = "cs"),
                )
            val out = r.bytesUtf8()
            out shouldContain "Aktivní"
            out shouldContain "Neaktivní"
        }

        "substituteValueLabels=false leaves raw values" {
            val r =
                DataFormatter.fromJsonRows(
                    sampleJson,
                    OutputFormat.MARKDOWN,
                    FormatOptions(
                        columnMetadata = decorations,
                        preferredLanguage = "cs",
                        substituteValueLabels = false,
                    ),
                )
            val out = r.bytesUtf8()
            out shouldNotContain "Aktivní"
            out shouldContain "| 1 |"
        }

        // ----- CSV / TSV -----

        "csv header + value substitution" {
            val r =
                DataFormatter.fromJsonRows(
                    sampleJson,
                    OutputFormat.CSV,
                    FormatOptions(columnMetadata = decorations, preferredLanguage = "en"),
                )
            val out = r.bytesUtf8()
            out shouldContain "id,Status"
            out shouldContain ",Active"
            out shouldContain ",Inactive"
        }

        "tsv header + value substitution" {
            val r =
                DataFormatter.fromJsonRows(
                    sampleJson,
                    OutputFormat.TSV,
                    FormatOptions(columnMetadata = decorations, preferredLanguage = "cs"),
                )
            val out = r.bytesUtf8()
            out shouldContain "id\tStav"
            out shouldContain "\tAktivní"
        }

        // ----- JSON -----

        "json keeps raw column keys; cells substituted to localised string" {
            val r =
                DataFormatter.fromJsonRows(
                    sampleJson,
                    OutputFormat.JSON,
                    FormatOptions(columnMetadata = decorations, preferredLanguage = "cs"),
                )
            val out = r.bytesUtf8()
            // Keys remain raw column names.
            out shouldContain "\"id\""
            out shouldContain "\"stav\""
            // Values now localised strings.
            out shouldContain "\"Aktivní\""
        }

        "json includeColumnLabels emits __columnLabels companion" {
            val r =
                DataFormatter.fromJsonRows(
                    sampleJson,
                    OutputFormat.JSON,
                    FormatOptions(
                        columnMetadata = decorations,
                        preferredLanguage = "cs",
                        includeColumnLabels = true,
                    ),
                )
            val out = r.bytesUtf8()
            out shouldContain "\"rows\""
            out shouldContain "\"__columnLabels\""
            out shouldContain "\"stav\":\"Stav\""
        }

        // ----- Defaults / no-op paths -----

        "no columnMetadata leaves output identical to a baseline run" {
            val baseline =
                DataFormatter.fromJsonRows(
                    sampleJson,
                    OutputFormat.MARKDOWN,
                    FormatOptions(),
                )
            val withEmpty =
                DataFormatter.fromJsonRows(
                    sampleJson,
                    OutputFormat.MARKDOWN,
                    FormatOptions(columnMetadata = emptyMap()),
                )
            withEmpty.bytesUtf8() shouldBe baseline.bytesUtf8()
        }

        "missing valueLabel for a cell falls through to raw value" {
            val partial =
                mapOf(
                    "stav" to
                        ColumnDecoration(
                            valueLabels =
                                mapOf("1" to LocalizedString(mapOf("cs" to "Aktivní"))),
                        ),
                )
            val r =
                DataFormatter.fromJsonRows(
                    sampleJson,
                    OutputFormat.MARKDOWN,
                    FormatOptions(columnMetadata = partial, preferredLanguage = "cs"),
                )
            val out = r.bytesUtf8()
            out shouldContain "Aktivní"
            // The "2" code wasn't decorated, so it survives raw.
            out shouldContain "| 2 |"
        }

        "case-insensitive language lookup hits CS / cs / Cs interchangeably" {
            val out =
                DataFormatter
                    .fromJsonRows(
                        sampleJson,
                        OutputFormat.MARKDOWN,
                        FormatOptions(columnMetadata = decorations, preferredLanguage = "CS"),
                    ).bytesUtf8()
            out shouldContain "Aktivní"
        }
    })
