package org.tatrman.query.e2e

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.FilterNode
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.DetectSchemaResponse
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.validate.v1.ValidateResponse
import com.google.protobuf.kotlin.toByteString
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.DispatcherClient
import org.tatrman.query.client.TranslatorClient
import org.tatrman.query.client.TranslatorDetectClient
import org.tatrman.query.client.TranslatorTranslateClient
import org.tatrman.query.client.ValidatorClient
import org.tatrman.query.grpc.QueryServiceImpl
import org.tatrman.query.retry.RetryPolicy
import java.time.Duration

class EntityScanE2ESpec :
    StringSpec({
        val customers =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("er")
                .setName("customers")
                .build()

        val customersDb =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("customers")
                .build()

        val tenantPredicate =
            org.tatrman.plan.v1.Expression
                .newBuilder()
                .setFunction(
                    FunctionCall
                        .newBuilder()
                        .setOperation("eq")
                        .addOperands(
                            org.tatrman.plan.v1.Expression
                                .newBuilder()
                                .setColumnRef(ColumnRef.newBuilder().setName("tenant_id"))
                                .build(),
                        ).addOperands(
                            org.tatrman.plan.v1.Expression
                                .newBuilder()
                                .setLiteral(Literal.newBuilder().setIntValue(1).setType("int"))
                                .build(),
                        ),
                ).setResultType("bool")
                .build()

        fun erPlanWithScan(qname: QualifiedName): PlanNode =
            PlanNode
                .newBuilder()
                .setScan(
                    ScanNode
                        .newBuilder()
                        .setObject(qname)
                        .addOutputColumns(ColumnRef.newBuilder().setName("id"))
                        .addOutputColumns(ColumnRef.newBuilder().setName("name"))
                        .addOutputColumns(ColumnRef.newBuilder().setName("tenant_id")),
                ).build()

        fun dbPlanWithScan(qname: QualifiedName): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode
                        .newBuilder()
                        .setTable(qname)
                        .addOutputColumns(ColumnRef.newBuilder().setName("id"))
                        .addOutputColumns(ColumnRef.newBuilder().setName("name"))
                        .addOutputColumns(ColumnRef.newBuilder().setName("tenant_id")),
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
                        .setSchemaFingerprint("fp-actual")
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

        "ER ScanNode plan flows through both validator passes" {
            runBlocking {
                val erPlan = erPlanWithScan(customers)
                val dbPlan = dbPlanWithScan(customersDb)

                var parseCallCount = 0
                val combinedTranslator =
                    TranslatorClient { req ->
                        parseCallCount++
                        val plan =
                            when (req.targetSchema) {
                                SchemaCode.ER -> erPlan
                                SchemaCode.DB -> dbPlan
                                else -> throw IllegalStateException("Unexpected target schema: ${req.targetSchema}")
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
                        val planAfterPass =
                            if (req.options.applySecurity && req.plan.hasScan()) {
                                PlanNode
                                    .newBuilder()
                                    .setFilter(
                                        FilterNode
                                            .newBuilder()
                                            .setInput(req.plan)
                                            .setCondition(tenantPredicate),
                                    ).build()
                            } else {
                                req.plan
                            }
                        ValidateResponse
                            .newBuilder()
                            .setPlan(planAfterPass)
                            .setContext(req.context)
                            .build()
                    }

                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                val detectStub: TranslatorDetectClient =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.CONFIRMED)
                            .setEffectiveSchema(SchemaCode.ER)
                            .build()
                    }
                val svc =
                    QueryServiceImpl(
                        combinedTranslator,
                        detectStub,
                        TranslatorTranslateClient { throw IllegalStateException("translate not used") },
                        validatorStub,
                        dispatcherStub,
                        cache,
                        nojitterRetry(),
                    )

                val req =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT id, name FROM customers")
                        .setSourceLanguage(Language.SQL)
                        .setContext(
                            org.tatrman.plan.v1.PipelineContext
                                .newBuilder()
                                .setUserId("u1")
                                .setModelVersion("v1"),
                        ).build()

                val result = svc.run(req).toList()

                result.size shouldBe 2
                parseCallCount shouldBe 2
                validatorPass shouldBe 2
            }
        }

        "validator pass 1 adds filter above ScanNode when security is applied" {
            runBlocking {
                val erPlan = erPlanWithScan(customers)
                val dbPlan = dbPlanWithScan(customersDb)

                var pass1Plan: PlanNode? = null
                var pass2Plan: PlanNode? = null

                val combinedTranslator =
                    TranslatorClient { req ->
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

                val validatorStub: ValidatorClient =
                    ValidatorClient { req ->
                        when (req.options.applySecurity) {
                            true -> {
                                if (req.plan.hasScan()) {
                                    val withFilter =
                                        PlanNode
                                            .newBuilder()
                                            .setFilter(
                                                FilterNode
                                                    .newBuilder()
                                                    .setInput(req.plan)
                                                    .setCondition(tenantPredicate),
                                            ).build()
                                    if (req.plan.hasScan()) {
                                        pass1Plan = withFilter
                                    } else {
                                        pass2Plan = withFilter
                                    }
                                    ValidateResponse
                                        .newBuilder()
                                        .setPlan(withFilter)
                                        .setContext(req.context)
                                        .build()
                                } else {
                                    pass2Plan = req.plan
                                    ValidateResponse
                                        .newBuilder()
                                        .setPlan(req.plan)
                                        .setContext(req.context)
                                        .build()
                                }
                            }
                            else ->
                                ValidateResponse
                                    .newBuilder()
                                    .setPlan(req.plan)
                                    .setContext(req.context)
                                    .build()
                        }
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
                    QueryServiceImpl(
                        combinedTranslator,
                        detectStub,
                        TranslatorTranslateClient { throw IllegalStateException() },
                        validatorStub,
                        dispatcherStub,
                        CompiledPlanCache(100, Duration.ofMinutes(60)),
                        nojitterRetry(),
                    )

                val req =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT id, name FROM customers")
                        .setSourceLanguage(Language.SQL)
                        .setContext(
                            org.tatrman.plan.v1.PipelineContext
                                .newBuilder()
                                .setUserId("u1")
                                .setModelVersion("v1"),
                        ).build()

                svc.run(req).toList()

                pass1Plan!!.hasFilter() shouldBe true
                pass1Plan!!.filter.input.hasScan() shouldBe true
            }
        }

        "ER plan without ScanNode passes through validator pass 1 as no-op" {
            runBlocking {
                val projectOnErScan =
                    PlanNode
                        .newBuilder()
                        .setProject(
                            ProjectNode
                                .newBuilder()
                                .setInput(erPlanWithScan(customers))
                                .addExpressions(
                                    org.tatrman.plan.v1.NamedExpression
                                        .newBuilder()
                                        .setExpression(
                                            org.tatrman.plan.v1.Expression
                                                .newBuilder()
                                                .setColumnRef(ColumnRef.newBuilder().setName("id")),
                                        ),
                                ),
                        ).build()

                var pass1CalledWith: PlanNode? = null
                var pass2CalledWith: PlanNode? = null

                val combinedTranslator: TranslatorClient =
                    TranslatorClient { req ->
                        val plan =
                            when (req.targetSchema) {
                                SchemaCode.ER -> projectOnErScan
                                SchemaCode.DB -> dbPlanWithScan(customersDb)
                                else -> throw IllegalStateException()
                            }
                        ParseResponse
                            .newBuilder()
                            .setPlan(plan)
                            .setContext(req.context)
                            .build()
                    }

                val validatorStub: ValidatorClient =
                    ValidatorClient { req ->
                        when (req.options.applySecurity) {
                            true -> {
                                val nodeCase = req.plan.nodeCase
                                if (nodeCase == PlanNode.NodeCase.SCAN || nodeCase == PlanNode.NodeCase.PROJECT) {
                                    pass1CalledWith = req.plan
                                } else {
                                    pass2CalledWith = req.plan
                                }
                                ValidateResponse
                                    .newBuilder()
                                    .setPlan(req.plan)
                                    .setContext(req.context)
                                    .build()
                            }
                            else ->
                                ValidateResponse
                                    .newBuilder()
                                    .setPlan(req.plan)
                                    .setContext(req.context)
                                    .build()
                        }
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
                    QueryServiceImpl(
                        combinedTranslator,
                        detectStub,
                        TranslatorTranslateClient { throw IllegalStateException() },
                        validatorStub,
                        dispatcherStub,
                        CompiledPlanCache(100, Duration.ofMinutes(60)),
                        nojitterRetry(),
                    )

                val req =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT id FROM customers")
                        .setSourceLanguage(Language.SQL)
                        .setContext(
                            org.tatrman.plan.v1.PipelineContext
                                .newBuilder()
                                .setUserId("u1")
                                .setModelVersion("v1"),
                        ).build()

                svc.run(req).toList()

                pass1CalledWith!!.hasProject() shouldBe true
                pass1CalledWith!!.project.input.hasScan() shouldBe true
                pass2CalledWith!!.hasTableScan() shouldBe true
            }
        }

        // Pins Review 010 §1: the second Translator call (target=DB, source=REL_NODE) must
        // receive the **validator-pass-1 output**, NOT the raw `erParsed.plan`. The bug
        // caught here is "parseAndCache does both Translator calls back-to-back and then the
        // pass-1 output is computed too late to affect the dispatched plan."
        "second Translator parse (target=DB) receives the validator pass-1 plan" {
            runBlocking {
                val erPlan = erPlanWithScan(customers)
                val dbPlan = dbPlanWithScan(customersDb)

                // Capture the source bytes the second Translator call (REL_NODE) sees.
                var secondParseSource: String? = null
                val combinedTranslator =
                    TranslatorClient { req ->
                        if (req.sourceLanguage == Language.REL_NODE) {
                            secondParseSource = req.source
                        }
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

                // Pass-1 wraps the ER scan in a Filter; pass-2 is a no-op for this test.
                val validatorStub: ValidatorClient =
                    ValidatorClient { req ->
                        val planOut =
                            if (req.options.applySecurity && req.plan.hasScan()) {
                                PlanNode
                                    .newBuilder()
                                    .setFilter(
                                        FilterNode
                                            .newBuilder()
                                            .setInput(req.plan)
                                            .setCondition(tenantPredicate),
                                    ).build()
                            } else {
                                req.plan
                            }
                        ValidateResponse
                            .newBuilder()
                            .setPlan(planOut)
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
                    QueryServiceImpl(
                        combinedTranslator,
                        detectStub,
                        TranslatorTranslateClient { throw IllegalStateException() },
                        validatorStub,
                        dispatcherStub,
                        CompiledPlanCache(100, Duration.ofMinutes(60)),
                        nojitterRetry(),
                    )

                val req =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT id, name FROM customers")
                        .setSourceLanguage(Language.SQL)
                        .setContext(
                            org.tatrman.plan.v1.PipelineContext
                                .newBuilder()
                                .setUserId("u1")
                                .setModelVersion("v1"),
                        ).build()

                svc.run(req).toList()

                // The second parse MUST have been called.
                secondParseSource shouldNotBe null
                // Decode the bytes (orchestrator uses Latin-1 — see Translator.parseRelNodeBytes).
                val decodedSecondSource =
                    PlanNode.parseFrom(secondParseSource!!.toByteArray(Charsets.ISO_8859_1))
                // It must be the validator-pass-1 OUTPUT (Filter wrapping the ER scan), NOT the
                // raw `erParsed.plan` (a bare Scan). A regression of the §1 fix would put a
                // bare Scan here.
                decodedSecondSource.hasFilter() shouldBe true
                decodedSecondSource.filter.input.hasScan() shouldBe true
            }
        }
    })
