package org.tatrman.kantheon.ariadne.resolve

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.kantheon.ariadne.model.Binding
import org.tatrman.kantheon.ariadne.model.CncSchema
import org.tatrman.kantheon.ariadne.model.DbSchema
import org.tatrman.kantheon.ariadne.model.DrillMap
import org.tatrman.kantheon.ariadne.model.ErSchema
import org.tatrman.kantheon.ariadne.model.LocalizedText
import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.model.ModelVersion
import org.tatrman.kantheon.ariadne.model.Query
import org.tatrman.kantheon.ariadne.model.QueryParameterDef
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant

/**
 * Stage 03 task 3-5 — DrillMapValidator.
 *
 * Each case builds a tiny [Model] containing two pattern queries + one drill
 * map and asserts the expected diagnostic surfaces (or not).
 */
class DrillMapValidatorSpec :
    StringSpec({

        "happy path — to/from resolve, args match parameters, value present in source SQL" {
            val model =
                modelWith(
                    queries =
                        listOf(
                            patternQuery(
                                "ucetni_zapisy_agregace_strediska",
                                params = emptyList(),
                                sql = "SELECT s.NAZEV, SUM(z.CASTKA) AS soucet, z.IDUCETZAP FROM ucet_zapis z",
                            ),
                            patternQuery(
                                "ucetni_doklad_detail",
                                params = listOf(QueryParameterDef("id_ucetniho_zapisu", "int")),
                                sql = "SELECT * FROM ucet_zapis WHERE IDUCETZAP = :id_ucetniho_zapisu",
                            ),
                        ),
                    drillMaps =
                        listOf(
                            drill(
                                name = "agg_strediska_na_doklad",
                                from = "ucetni_zapisy_agregace_strediska",
                                to = "ucetni_doklad_detail",
                                args = mapOf("id_ucetniho_zapisu" to "IDUCETZAP"),
                            ),
                        ),
                )

            DrillMapValidator().validate(model).errors.shouldBeEmpty()
        }

        "DRILL_MAP_UNKNOWN_TARGET — to references a non-existent pattern" {
            val model =
                modelWith(
                    queries =
                        listOf(
                            patternQuery("ucetni_zapisy_agregace_strediska", emptyList(), "SELECT IDUCETZAP"),
                        ),
                    drillMaps =
                        listOf(
                            drill(
                                name = "agg_strediska_na_doklad",
                                from = "ucetni_zapisy_agregace_strediska",
                                to = "ucetni_doklad_detail",
                                args = mapOf("id_ucetniho_zapisu" to "IDUCETZAP"),
                            ),
                        ),
                )

            val errors = DrillMapValidator().validate(model).errors
            errors.size shouldBe 1
            errors[0].message shouldContain "DRILL_MAP_UNKNOWN_TARGET"
        }

        "DRILL_MAP_UNKNOWN_PARAM — args key is not a parameter on to" {
            val model =
                modelWith(
                    queries =
                        listOf(
                            patternQuery("a", emptyList(), "SELECT 1"),
                            patternQuery(
                                "b",
                                params = listOf(QueryParameterDef("id_ucetniho_zapisu", "int")),
                                sql = "SELECT * WHERE IDUCETZAP = :id_ucetniho_zapisu",
                            ),
                        ),
                    drillMaps =
                        listOf(
                            drill(
                                name = "x",
                                from = "a",
                                to = "b",
                                args = mapOf("not_a_param" to "1", "id_ucetniho_zapisu" to "1"),
                            ),
                        ),
                )

            val errors = DrillMapValidator().validate(model).errors
            errors.any { it.message.contains("DRILL_MAP_UNKNOWN_PARAM") && it.message.contains("not_a_param") } shouldBe
                true
        }

        "DRILL_MAP_PARAM_NOT_IN_FROM_SQL — args value (bare id) is not found in from.sourceText" {
            val model =
                modelWith(
                    queries =
                        listOf(
                            patternQuery(
                                "a",
                                emptyList(),
                                sql = "SELECT NAZEV, CASTKA FROM doklad",
                            ),
                            patternQuery(
                                "b",
                                params = listOf(QueryParameterDef("id_ucetniho_zapisu", "int")),
                                sql = "SELECT 1",
                            ),
                        ),
                    drillMaps =
                        listOf(
                            drill(
                                name = "x",
                                from = "a",
                                to = "b",
                                // IDUCETZAP isn't in the from SQL → typo
                                args = mapOf("id_ucetniho_zapisu" to "IDUCETZAP"),
                            ),
                        ),
                )

            val errors = DrillMapValidator().validate(model).errors
            errors.any { it.message.contains("DRILL_MAP_PARAM_NOT_IN_FROM_SQL") } shouldBe true
        }

        "DRILL_MAP_MISSING_PARAM — a required parameter on to has no args entry" {
            val model =
                modelWith(
                    queries =
                        listOf(
                            patternQuery("a", emptyList(), "SELECT IDUCETZAP"),
                            patternQuery(
                                "b",
                                params =
                                    listOf(
                                        QueryParameterDef("id_ucetniho_zapisu", "int"),
                                        QueryParameterDef("rok", "int"),
                                    ),
                                sql = "SELECT 1",
                            ),
                        ),
                    drillMaps =
                        listOf(
                            drill(
                                name = "x",
                                from = "a",
                                to = "b",
                                args = mapOf("id_ucetniho_zapisu" to "IDUCETZAP"),
                            ),
                        ),
                )

            val errors = DrillMapValidator().validate(model).errors
            errors.any { it.message.contains("DRILL_MAP_MISSING_PARAM") && it.message.contains("rok") } shouldBe true
        }

        "string literal in args value — bypasses the from-SQL substring check" {
            val model =
                modelWith(
                    queries =
                        listOf(
                            patternQuery("a", emptyList(), "SELECT NAZEV FROM x"),
                            patternQuery(
                                "b",
                                params = listOf(QueryParameterDef("mode", "string")),
                                sql = "SELECT 1",
                            ),
                        ),
                    drillMaps =
                        listOf(
                            drill(
                                name = "x",
                                from = "a",
                                to = "b",
                                args = mapOf("mode" to "'detail'"),
                            ),
                        ),
                )

            DrillMapValidator().validate(model).errors.shouldBeEmpty()
        }
    })

private fun qn(
    schemaCode: String,
    namespace: String,
    name: String,
): QualifiedName {
    // SchemaCode proto enum only covers DB/ER/CNC/WS/OBJ — query/map fall through
    // to UNSPECIFIED, matching the production loader's behaviour in Source.qname().
    val code =
        try {
            SchemaCode.valueOf(schemaCode.uppercase())
        } catch (_: IllegalArgumentException) {
            SchemaCode.SCHEMA_CODE_UNSPECIFIED
        }
    return QualifiedName
        .newBuilder()
        .setSchemaCode(code)
        .setNamespace(namespace)
        .setName(name)
        .build()
}

private fun patternQuery(
    name: String,
    params: List<QueryParameterDef>,
    sql: String,
): Query {
    val q = qn("query", "query", name)
    return Query(
        internalId = "query:query.query.$name",
        qname = q,
        sourceLanguage = "SQL",
        sourceText = sql,
        parameters = params,
    )
}

private fun drill(
    name: String,
    from: String,
    to: String,
    args: Map<String, String>,
): DrillMap {
    val q = qn("query", "drill", name)
    return DrillMap(
        internalId = "query.drill_map:query.drill.$name",
        qname = q,
        sourceFile = "test.ttr",
        fromPattern = qn("query", "query", from),
        toPattern = qn("query", "query", to),
        argMapping = args,
        display = LocalizedText(byLanguage = mapOf("cs" to "Detail")),
        binding = Binding.BoundReal,
    )
}

private fun modelWith(
    queries: List<Query>,
    drillMaps: List<DrillMap>,
): Model =
    Model(
        descriptor = ModelDescriptor(id = "test", name = "test"),
        version = ModelVersion(value = "1", swappedAt = Instant.now()),
        schemas =
            mapOf(
                "db" to DbSchema(),
                "er" to ErSchema(),
                "cnc" to CncSchema(),
            ),
        mappings = emptyList(),
        queries = queries.associateBy { it.qname },
        drillMaps = drillMaps.associateBy { it.qname },
    )
