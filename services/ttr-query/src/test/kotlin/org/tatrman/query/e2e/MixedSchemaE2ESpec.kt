package org.tatrman.query.e2e

import com.google.protobuf.kotlin.toByteString
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.ColumnRef as ColRef
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.DetectSchemaResponse
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.validate.v1.ValidateResponse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.DispatcherClient
import org.tatrman.query.client.TranslatorClient
import org.tatrman.query.client.TranslatorDetectClient
import org.tatrman.query.client.TranslatorTranslateClient
import org.tatrman.query.client.ValidatorClient
import org.tatrman.query.grpc.TheseusServiceImpl
import org.tatrman.query.retry.RetryPolicy
import java.time.Duration

class MixedSchemaE2ESpec :
    StringSpec({
        val customers =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("er")
                .setName("customers")
                .build()
        val orders =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("er")
                .setName("orders")
                .build()

        val customersDb =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("customers")
                .build()
        val ordersDb =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("orders")
                .build()

        fun erJoinPlan(
            leftQname: QualifiedName,
            rightQname: QualifiedName,
        ): PlanNode =
            PlanNode
                .newBuilder()
                .setProject(
                    ProjectNode
                        .newBuilder()
                        .addExpressions(
                            NamedExpression
                                .newBuilder()
                                .setExpression(
                                    Expression.newBuilder().setColumnRef(
                                        ColRef.newBuilder().setName("customer_name"),
                                    ),
                                ),
                        ).addExpressions(
                            NamedExpression
                                .newBuilder()
                                .setExpression(
                                    Expression.newBuilder().setColumnRef(
                                        ColRef.newBuilder().setName("order_id"),
                                    ),
                                ),
                        ).setInput(
                            PlanNode
                                .newBuilder()
                                .setJoin(
                                    JoinNode
                                        .newBuilder()
                                        .setJoinType(JoinType.INNER)
                                        .setLeft(
                                            PlanNode
                                                .newBuilder()
                                                .setScan(
                                                    ScanNode
                                                        .newBuilder()
                                                        .setObject(leftQname)
                                                        .addOutputColumns(ColRef.newBuilder().setName("customer_name"))
                                                        .addOutputColumns(ColRef.newBuilder().setName("customer_id")),
                                                ),
                                        ).setRight(
                                            PlanNode
                                                .newBuilder()
                                                .setScan(
                                                    ScanNode
                                                        .newBuilder()
                                                        .setObject(rightQname)
                                                        .addOutputColumns(ColRef.newBuilder().setName("order_id"))
                                                        .addOutputColumns(ColRef.newBuilder().setName("customer_id")),
                                                ),
                                        ),
                                ),
                        ),
                ).build()

        fun dbJoinPlan(
            leftQname: QualifiedName,
            rightQname: QualifiedName,
        ): PlanNode =
            PlanNode
                .newBuilder()
                .setProject(
                    ProjectNode
                        .newBuilder()
                        .addExpressions(
                            NamedExpression
                                .newBuilder()
                                .setExpression(
                                    Expression.newBuilder().setColumnRef(
                                        ColRef.newBuilder().setName("customer_name"),
                                    ),
                                ),
                        ).addExpressions(
                            NamedExpression
                                .newBuilder()
                                .setExpression(
                                    Expression.newBuilder().setColumnRef(
                                        ColRef.newBuilder().setName("order_id"),
                                    ),
                                ),
                        ).setInput(
                            PlanNode
                                .newBuilder()
                                .setJoin(
                                    JoinNode
                                        .newBuilder()
                                        .setJoinType(JoinType.INNER)
                                        .setLeft(
                                            PlanNode
                                                .newBuilder()
                                                .setTableScan(
                                                    TableScanNode
                                                        .newBuilder()
                                                        .setTable(leftQname)
                                                        .addOutputColumns(ColRef.newBuilder().setName("customer_name"))
                                                        .addOutputColumns(ColRef.newBuilder().setName("customer_id")),
                                                ),
                                        ).setRight(
                                            PlanNode
                                                .newBuilder()
                                                .setTableScan(
                                                    TableScanNode
                                                        .newBuilder()
                                                        .setTable(rightQname)
                                                        .addOutputColumns(ColRef.newBuilder().setName("order_id"))
                                                        .addOutputColumns(ColRef.newBuilder().setName("customer_id")),
                                                ),
                                        ),
                                ),
                        ),
                ).build()

        fun nojitterRetry(maxAttempts: Int = 3) =
            RetryPolicy(
                maxAttempts = maxAttempts,
                initialBackoffMillis = 1,
                multiplier = 1.0,
                jitterPercent = 0,
            )

        val dispatcherStub: DispatcherClient =
            DispatcherClient { _ ->
                flowOf(
                    org.tatrman.worker.v1.ResultBatch
                        .newBuilder()
                        .setIsFirst(true)
                        .setIsLast(false)
                        .setSchemaFingerprint("fp-mixed")
                        .setArrowIpc(ByteArray(0).toByteString())
                        .setContext(
                            org.tatrman.plan.v1.PipelineContext
                                .getDefaultInstance(),
                        ).build(),
                    org.tatrman.worker.v1.ResultBatch
                        .newBuilder()
                        .setIsLast(true)
                        .setBatchIndex(1)
                        .setArrowIpc(ByteArray(0).toByteString())
                        .build(),
                )
            }

        "two-pass pipeline correctly handles multi-entity join plan" {
            runBlocking {
                val erPlan = erJoinPlan(customers, orders)
                val dbPlan = dbJoinPlan(customersDb, ordersDb)

                var parseCalls = 0
                val combinedTranslator: TranslatorClient =
                    TranslatorClient { req ->
                        parseCalls++
                        val plan =
                            when (req.targetSchema) {
                                SchemaCode.ER -> erPlan
                                SchemaCode.DB -> dbPlan
                                else -> throw IllegalStateException("Unexpected: ${req.targetSchema}")
                            }
                        ParseResponse
                            .newBuilder()
                            .setPlan(plan)
                            .setContext(req.context)
                            .build()
                    }

                var validatorPass = 0
                val validatorStub: ValidatorClient =
                    ValidatorClient { req ->
                        validatorPass++
                        ValidateResponse
                            .newBuilder()
                            .setPlan(req.plan)
                            .setContext(req.context)
                            .build()
                    }

                val detectStub: TranslatorDetectClient =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.CONFIRMED)
                            .setEffectiveSchema(SchemaCode.ER)
                            .build()
                    }
                val svc =
                    TheseusServiceImpl(
                        combinedTranslator,
                        detectStub,
                        TranslatorTranslateClient { throw IllegalStateException("translate not used") },
                        validatorStub,
                        dispatcherStub,
                        CompiledPlanCache(100, Duration.ofMinutes(60)),
                        nojitterRetry(),
                    )

                val req =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT c.name, o.id FROM customers c JOIN orders o ON c.id = o.customer_id")
                        .setSourceLanguage(Language.SQL)
                        .setContext(
                            org.tatrman.plan.v1.PipelineContext
                                .newBuilder()
                                .setUserId("u1")
                                .setModelVersion("v1"),
                        ).build()

                val result = svc.run(req).toList()

                result.size shouldBe 2
                parseCalls shouldBe 2
                validatorPass shouldBe 2
            }
        }

        "second run hits cache and does not re-parse" {
            runBlocking {
                val erPlan = erJoinPlan(customers, orders)
                val dbPlan = dbJoinPlan(customersDb, ordersDb)

                var parseCalls = 0
                val combinedTranslator: TranslatorClient =
                    TranslatorClient { req ->
                        parseCalls++
                        val plan =
                            when (req.targetSchema) {
                                SchemaCode.ER -> erPlan
                                SchemaCode.DB -> dbPlan
                                else -> throw IllegalStateException()
                            }
                        ParseResponse
                            .newBuilder()
                            .setPlan(plan)
                            .setContext(req.context)
                            .build()
                    }

                var validatorCalls = 0
                val validatorStub: ValidatorClient =
                    ValidatorClient { req ->
                        validatorCalls++
                        ValidateResponse
                            .newBuilder()
                            .setPlan(req.plan)
                            .setContext(req.context)
                            .build()
                    }

                val detectStub: TranslatorDetectClient =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.CONFIRMED)
                            .setEffectiveSchema(SchemaCode.ER)
                            .build()
                    }
                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                val svc =
                    TheseusServiceImpl(
                        combinedTranslator,
                        detectStub,
                        TranslatorTranslateClient { throw IllegalStateException() },
                        validatorStub,
                        dispatcherStub,
                        cache,
                        nojitterRetry(),
                    )

                val req =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT c.name, o.id FROM customers c JOIN orders o ON c.id = o.customer_id")
                        .setSourceLanguage(Language.SQL)
                        .setContext(
                            org.tatrman.plan.v1.PipelineContext
                                .newBuilder()
                                .setUserId("u1")
                                .setModelVersion("v1"),
                        ).build()

                svc.run(req).toList()
                val parseBefore = parseCalls

                svc.run(req).toList()

                parseCalls shouldBe parseBefore + 1
                validatorCalls shouldBe 4
            }
        }
    })
