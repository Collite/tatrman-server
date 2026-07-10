package org.tatrman.kantheon.proteus.grpc

import org.tatrman.ariadne.v1.GetQueryResponse
import org.tatrman.ariadne.v1.ParseStatus as MetadataParseStatus
import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.proteus.v1.Language
import org.tatrman.proteus.v1.ParseRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.proteus.model.BootFixtureModel
import org.tatrman.kantheon.proteus.model.StaticModelHandleProvider

class TranslatorQueryRefSpec :
    StringSpec({

        fun dbQn(name: String) =
            QualifiedName
                .newBuilder()
                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                .setNamespace("dbo")
                .setName(name)
                .build()

        // Stand-in for a stored query's canonical form.
        val storedPlan: PlanNode =
            PlanNode.newBuilder().setTableScan(TableScanNode.newBuilder().setTable(dbQn("customers"))).build()

        // A TransDSL source that references a stored query and projects nothing extra → the parsed
        // plan is just the SubqueryNode for that ref.
        val transDslWithQueryRef = """{"core":[{"queryRef":"obj.q.findCustomers","alias":"fc"}]}"""

        fun serviceWith(response: GetQueryResponse) =
            TranslatorServiceImpl(
                StaticModelHandleProvider(BootFixtureModel.handle()),
                getQuery = { response },
            )

        suspend fun parseTransDsl(svc: TranslatorServiceImpl) =
            svc.parseToRelNode(
                ParseRequest
                    .newBuilder()
                    .setSource(
                        transDslWithQueryRef,
                    ).setSourceLanguage(Language.TRANSFORMATION_DSL)
                    .build(),
            )

        "TransDSL query_ref resolves to the stored canonical form" {
            val resp =
                parseTransDsl(
                    serviceWith(
                        GetQueryResponse
                            .newBuilder()
                            .setParseStatus(MetadataParseStatus.PARSE_STATUS_PARSED)
                            .setCanonicalForm(storedPlan)
                            .build(),
                    ),
                )
            resp.messagesList shouldHaveSize 0
            resp.hasPlan() shouldBe true
            resp.plan.hasSubquery() shouldBe true
            resp.plan.subquery.alias shouldBe "fc"
            resp.plan.subquery.subquery shouldBe storedPlan
        }

        "TransDSL query_ref still pending → query_ref_pending message, no plan" {
            val resp =
                parseTransDsl(
                    serviceWith(
                        GetQueryResponse.newBuilder().setParseStatus(MetadataParseStatus.PARSE_STATUS_PENDING).build(),
                    ),
                )
            resp.hasPlan() shouldBe false
            resp.messagesList shouldHaveSize 1
            resp.messagesList[0].code shouldBe "query_ref_pending"
        }

        "TransDSL query_ref failed to parse upstream → query_ref_parse_failed message" {
            val resp =
                parseTransDsl(
                    serviceWith(
                        GetQueryResponse
                            .newBuilder()
                            .setParseStatus(MetadataParseStatus.PARSE_STATUS_FAILED)
                            .setParseErrorMessage("syntax error near 'X'")
                            .build(),
                    ),
                )
            resp.hasPlan() shouldBe false
            resp.messagesList[0].code shouldBe "query_ref_parse_failed"
        }

        "TransDSL query_ref unknown → query_ref_not_found message" {
            val resp =
                parseTransDsl(
                    serviceWith(
                        GetQueryResponse
                            .newBuilder()
                            .addMessages(
                                ResponseMessage.newBuilder().setSeverity(Severity.WARNING).setCode("object_not_found"),
                            ).build(),
                    ),
                )
            resp.hasPlan() shouldBe false
            resp.messagesList[0].code shouldBe "query_ref_not_found"
        }

        "without a metadata client, query_ref falls back to the placeholder subquery (no error)" {
            val svc = TranslatorServiceImpl(StaticModelHandleProvider(BootFixtureModel.handle())) // getQuery = null
            val resp = parseTransDsl(svc)
            resp.messagesList shouldHaveSize 0
            resp.hasPlan() shouldBe true
            resp.plan.hasSubquery() shouldBe true
            resp.plan.subquery.alias shouldBe "fc"
            resp.plan.subquery.hasSubquery() shouldBe false // placeholder, not resolved
        }
    })
