package org.tatrman.query.grpc

import com.google.protobuf.kotlin.toByteString
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.query.v1.GetStatusRequest
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.DetectSchemaResponse
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.translate.v1.TranslateRequest
import org.tatrman.translate.v1.TranslateResponse
import org.tatrman.validate.v1.ValidateResponse
import org.tatrman.worker.v1.ExecutionOptions
import org.tatrman.worker.v1.ResultBatch
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
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
import org.tatrman.query.retry.RetryPolicy
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class TheseusServiceImplSpec :
    StringSpec({
        val customers =
            QualifiedName
                .newBuilder()
                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                .setNamespace("dbo")
                .setName("customers")
                .build()
        val planAfterParse =
            PlanNode
                .newBuilder()
                .setTableScan(TableScanNode.newBuilder().setTable(customers))
                .build()

        // A plan whose TableScan name encodes the source — lets a test tell two
        // distinct compiled plans apart at the dispatcher edge (T3 cache replay).
        fun planFor(source: String): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode.newBuilder().setTable(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                            .setNamespace("dbo")
                            .setName(source),
                    ),
                ).build()

        val detectStub: TranslatorDetectClient =
            TranslatorDetectClient {
                DetectSchemaResponse
                    .newBuilder()
                    .setDecision(org.tatrman.translate.v1.SchemaDecision.CONFIRMED)
                    .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.ER)
                    .build()
            }

        val parseStub: TranslatorClient =
            TranslatorClient { req ->
                ParseResponse
                    .newBuilder()
                    .setPlan(planAfterParse)
                    .setContext(req.context)
                    .build()
            }
        val translateStub: TranslatorTranslateClient =
            TranslatorTranslateClient { req ->
                TranslateResponse
                    .newBuilder()
                    .setOutput("OUT")
                    .setContext(req.context)
                    .build()
            }
        val validatorStub: ValidatorClient =
            ValidatorClient { req ->
                ValidateResponse
                    .newBuilder()
                    .setPlan(req.plan)
                    .setContext(req.context)
                    .build()
            }
        val dispatcherStub: DispatcherClient =
            DispatcherClient { _ ->
                flowOf(
                    ResultBatch
                        .newBuilder()
                        .setIsFirst(true)
                        .setIsLast(false)
                        .setSchemaFingerprint("fp-actual")
                        .setArrowIpc(ByteArray(0).toByteString())
                        .setContext(PipelineContext.getDefaultInstance())
                        .build(),
                    ResultBatch
                        .newBuilder()
                        .setIsLast(true)
                        .setBatchIndex(1)
                        .setArrowIpc(ByteArray(0).toByteString())
                        .build(),
                )
            }

        val nojitterRetry =
            RetryPolicy(
                maxAttempts = 3,
                initialBackoffMillis = 1,
                multiplier = 1.0,
                jitterPercent = 0,
            )

        fun service(
            translator: TranslatorClient = parseStub,
            translatorDetect: TranslatorDetectClient = detectStub,
            translatorTranslate: TranslatorTranslateClient = translateStub,
            validator: ValidatorClient = validatorStub,
            dispatcher: DispatcherClient = dispatcherStub,
            cache: CompiledPlanCache = CompiledPlanCache(100, Duration.ofMinutes(60)),
            retry: RetryPolicy = nojitterRetry,
        ): TheseusServiceImpl =
            TheseusServiceImpl(
                translator,
                translatorDetect,
                translatorTranslate,
                validator,
                dispatcher,
                cache,
                retry,
            )

        "Run streams Dispatcher batches and annotates the first with cache_miss + compile_duration_ms" {
            runBlocking {
                val resp =
                    service()
                        .run(
                            RunRequest
                                .newBuilder()
                                .setSource("SELECT id FROM customers")
                                .setSourceLanguage(Language.SQL)
                                .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                                .setExecutionOptions(ExecutionOptions.getDefaultInstance())
                                .build(),
                        ).toList()
                resp.size shouldBe 2
                val codes = resp[0].context.warningsList.map { it.code }
                codes shouldContain "compile_duration_ms"
                codes shouldContain "cache_miss"
            }
        }

        "second identical Run hits the cache" {
            runBlocking {
                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                val svc = service(cache = cache)
                val req =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT id FROM customers")
                        .setSourceLanguage(Language.SQL)
                        .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                        .build()
                svc.run(req).toList() // populate
                val second = svc.run(req).toList()
                val codes = second[0].context.warningsList.map { it.code }
                codes shouldContain "cache_hit"
                cache.stats().hits shouldBe 1
            }
        }

        // Fork Stage 3.5 T3 — cache REPLAY must serve the right compiled plan for the right
        // args, not merely "dispatcher called once". Inherited trap (ai-platform CLAUDE.md):
        // call-counting mocks miss cache-replay bugs (wrong-entry / shared-mutable-plan / key
        // collision). Two distinct queries → two entries; replaying query 1 must dispatch
        // query 1's plan, captured at Kyklop's edge.
        "cache replay dispatches the right compiled plan for the args (not a sibling entry)" {
            runBlocking {
                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                val dispatchedPlans = CopyOnWriteArrayList<PlanNode>()
                val capturingDispatcher =
                    DispatcherClient { req ->
                        dispatchedPlans.add(req.plan)
                        flowOf(
                            ResultBatch
                                .newBuilder()
                                .setIsFirst(true)
                                .setIsLast(true)
                                .setArrowIpc(ByteArray(0).toByteString())
                                .setContext(PipelineContext.getDefaultInstance())
                                .build(),
                        )
                    }
                // DB path: each source parses to a DISTINCT physical plan (table name = source),
                // so a wrong-entry replay would dispatch the other query's table.
                val perSourceParse =
                    TranslatorClient { req ->
                        ParseResponse
                            .newBuilder()
                            .setPlan(planFor(req.source))
                            .setContext(req.context)
                            .build()
                    }
                val dbDetect =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.CONFIRMED)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.DB)
                            .build()
                    }
                val svc =
                    service(
                        translator = perSourceParse,
                        translatorDetect = dbDetect,
                        dispatcher = capturingDispatcher,
                        cache = cache,
                    )

                fun req(sql: String) =
                    RunRequest
                        .newBuilder()
                        .setSource(sql)
                        .setSourceLanguage(Language.SQL)
                        .setSourceSchema(org.tatrman.plan.v1.SchemaCode.DB)
                        .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                        .build()

                svc.run(req("SELECT id FROM customers")).toList() // miss → entry A
                svc.run(req("SELECT id FROM orders")).toList() // miss → entry B
                cache.stats().entries shouldBe 2

                dispatchedPlans.clear()
                val replay = svc.run(req("SELECT id FROM customers")).toList()

                replay[0].context.warningsList.map { it.code } shouldContain "cache_hit"
                cache.stats().hits shouldBe 1
                // The replay dispatched query A's compiled plan — NOT query B's.
                dispatchedPlans.size shouldBe 1
                dispatchedPlans[0].tableScan.table.name shouldBe "SELECT id FROM customers"
            }
        }

        "bypass_cache forces a fresh translator call even with a populated cache" {
            runBlocking {
                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                var translatorCalls = 0
                val countingTranslator =
                    TranslatorClient { req ->
                        translatorCalls++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc = service(translator = countingTranslator, cache = cache)
                val req =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT id FROM customers")
                        .setSourceLanguage(Language.SQL)
                        .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                        .build()
                svc.run(req).toList()
                svc.run(req.toBuilder().setBypassCache(true).build()).toList()
                translatorCalls shouldBe 4
            }
        }

        "Run retries Translator UNAVAILABLE before first batch" {
            runBlocking {
                var calls = 0
                val flapping =
                    TranslatorClient { req ->
                        calls++
                        if (calls < 2) throw StatusException(Status.UNAVAILABLE)
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                service(translator = flapping)
                    .run(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT 1")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    ).toList()
                calls shouldBe 3
            }
        }

        "Run surfaces translator_unavailable after retries exhausted" {
            runBlocking {
                val alwaysDown =
                    TranslatorClient { _ ->
                        throw StatusException(Status.UNAVAILABLE.withDescription("down"))
                    }
                val out =
                    service(translator = alwaysDown)
                        .run(
                            RunRequest
                                .newBuilder()
                                .setSource("SELECT 1")
                                .setSourceLanguage(Language.SQL)
                                .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                                .build(),
                        ).toList()
                out.size shouldBe 1
                out[0].messagesList[0].code shouldBe "translator_unavailable"
            }
        }

        "Translator's structured ERROR message surfaces as translator_rejected" {
            runBlocking {
                val rejecting =
                    TranslatorClient { req ->
                        ParseResponse
                            .newBuilder()
                            .setContext(req.context)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("validation_failed")
                                    .setHumanMessage("bad SQL"),
                            ).build()
                    }
                val out =
                    service(translator = rejecting)
                        .run(
                            RunRequest
                                .newBuilder()
                                .setSource("nonsense")
                                .setSourceLanguage(Language.SQL)
                                .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                                .build(),
                        ).toList()
                out[0].messagesList[0].code shouldBe "translator_rejected"
            }
        }

        "Compile returns predicted_schema_fingerprint" {
            runBlocking {
                val resp =
                    service().compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM customers")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                resp.predictedSchemaFingerprint.length shouldBe 64
            }
        }

        "Compile surfaces translator error via messages" {
            runBlocking {
                val rejecting =
                    TranslatorClient { req ->
                        ParseResponse
                            .newBuilder()
                            .setContext(req.context)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("validation_failed")
                                    .setHumanMessage("bad"),
                            ).build()
                    }
                val resp =
                    service(translator = rejecting).compile(
                        RunRequest
                            .newBuilder()
                            .setSource("nonsense")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                resp.messagesList[0].code shouldBe "validation_failed"
            }
        }

        "Translate is a passthrough to Translator.Translate" {
            runBlocking {
                val resp =
                    service().translate(
                        TranslateRequest
                            .newBuilder()
                            .setSource("SELECT 1")
                            .setSourceLanguage(Language.SQL)
                            .setTargetLanguage(Language.SQL)
                            .build(),
                    )
                resp.output shouldBe "OUT"
            }
        }

        "GetStatus reports cache stats" {
            runBlocking {
                val resp = service().getStatus(GetStatusRequest.getDefaultInstance())
                resp.ready shouldBe true
                resp.compiledCache.maxEntries shouldBe 100L
            }
        }

        "Compile: AUTODETECTED-DB calls parse once with sourceSchema=DB, targetSchema=DB, then validates once" {
            runBlocking {
                var parseCallCount = 0
                val dbDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.AUTODETECTED)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.DB)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.INFO)
                                    .setCode("schema_autodetected")
                                    .setHumanMessage("Schema auto-detected as DB"),
                            ).build()
                    }
                val dbParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = dbParseStub,
                        translatorDetect = dbDetectStub,
                    )
                val resp =
                    svc.compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM qskupzbozi_df")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                parseCallCount shouldBe 1
                resp.messagesList.isNotEmpty() shouldBe true
                resp.messagesList[0].code shouldBe "schema_autodetected"
            }
        }

        "Compile: AUTODETECTED-ER falls back to two-pass ER path" {
            runBlocking {
                var parseCallCount = 0
                val erDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.AUTODETECTED)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.ER)
                            .build()
                    }
                val twoPassParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = twoPassParseStub,
                        translatorDetect = erDetectStub,
                    )
                val resp =
                    svc.compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM customers")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                parseCallCount shouldBe 2
            }
        }

        "Compile: CORRECTED carries WARNING message" {
            runBlocking {
                val correctedDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.CORRECTED)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.DB)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.WARNING)
                                    .setCode("schema_corrected")
                                    .setHumanMessage("Stated schema ER did not match detected DB"),
                            ).build()
                    }
                val svc = service(translatorDetect = correctedDetectStub)
                val resp =
                    svc.compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM qskupzbozi_df")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                resp.messagesList.any { it.code == "schema_corrected" } shouldBe true
            }
        }

        "Compile: AMBIGUOUS returns messages only, no parse/validate calls" {
            runBlocking {
                var parseCallCount = 0
                val ambiguousDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.AMBIGUOUS)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("schema_ambiguous")
                                    .setHumanMessage("Query spans multiple schemas"),
                            ).build()
                    }
                val countingParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = countingParseStub,
                        translatorDetect = ambiguousDetectStub,
                    )
                val resp =
                    svc.compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM customers JOIN orders ON ...")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                parseCallCount shouldBe 0
                resp.messagesList[0].code shouldBe "schema_ambiguous"
            }
        }

        "Compile: UNKNOWN returns messages only with suggestions, no parse/validate calls" {
            runBlocking {
                var parseCallCount = 0
                val unknownDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.UNKNOWN)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("schema_object_unknown")
                                    .setHumanMessage("Table QSKUPZBOZIX not found"),
                            ).build()
                    }
                val countingParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = countingParseStub,
                        translatorDetect = unknownDetectStub,
                    )
                val resp =
                    svc.compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM qskupzbozix")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                parseCallCount shouldBe 0
                resp.messagesList[0].code shouldBe "schema_object_unknown"
            }
        }

        "Compile: MIXED returns messages only, no parse/validate calls" {
            runBlocking {
                var parseCallCount = 0
                val mixedDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.MIXED)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("schema_mixed")
                                    .setHumanMessage("Identifiers span multiple schemas"),
                            ).build()
                    }
                val countingParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = countingParseStub,
                        translatorDetect = mixedDetectStub,
                    )
                val resp =
                    svc.compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM db.table JOIN er.entity ON ...")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                parseCallCount shouldBe 0
                resp.messagesList[0].code shouldBe "schema_mixed"
            }
        }

        "Compile: NOT_APPLICABLE uses legacy two-pass path" {
            runBlocking {
                var parseCallCount = 0
                val naDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.NOT_APPLICABLE)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                            .build()
                    }
                val countingParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = countingParseStub,
                        translatorDetect = naDetectStub,
                    )
                val resp =
                    svc.compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM customers")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                parseCallCount shouldBe 2
            }
        }

        "second identical Compile on a DB query replays the cached physical plan" {
            runBlocking {
                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                var parseCallCount = 0
                val dbDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.AUTODETECTED)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.DB)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.INFO)
                                    .setCode("schema_autodetected")
                                    .setHumanMessage("Schema auto-detected as DB"),
                            ).build()
                    }
                val dbParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = dbParseStub,
                        translatorDetect = dbDetectStub,
                        cache = cache,
                    )
                val req =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT id FROM qskupzbozi_df")
                        .setSourceLanguage(Language.SQL)
                        .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                        .build()
                val first = svc.compile(req)
                val second = svc.compile(req)
                parseCallCount shouldBe 1
                first.plan shouldBe second.plan
            }
        }

        "second identical Run on a DB query replays the cached physical plan" {
            runBlocking {
                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                var parseCallCount = 0
                val dbDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.AUTODETECTED)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.DB)
                            .build()
                    }
                val dbParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = dbParseStub,
                        translatorDetect = dbDetectStub,
                        cache = cache,
                    )
                val req =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT id FROM qskupzbozi_df")
                        .setSourceLanguage(Language.SQL)
                        .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                        .build()
                val first = svc.run(req).toList()
                val second = svc.run(req).toList()
                parseCallCount shouldBe 1
                first.size shouldBe 2
                first
                    .first()
                    .context.warningsList
                    .any { it.code == "cache_miss" } shouldBe true
                second.size shouldBe 2
                second
                    .first()
                    .context.warningsList
                    .any { it.code == "cache_hit" } shouldBe true
            }
        }

        "Compile (ER path) forwards a parse validation_failed at the ER parse" {
            runBlocking {
                var parseCallCount = 0
                val rejecting =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setContext(req.context)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("validation_failed")
                                    .setHumanMessage(
                                        "Object 'customers' not found — 'customers' is a db object; set source_schema=DB",
                                    ),
                            ).build()
                    }
                // Default detect stub resolves CONFIRMED/ER → ER path. The ER-parse ERROR must be
                // forwarded straight away (parse called once), not swallowed and re-derived via the
                // REL_NODE→DB re-parse.
                val resp =
                    service(translator = rejecting).compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM customer")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                resp.messagesList[0].code shouldBe "validation_failed"
                resp.messagesList[0].humanMessage.contains("is a db object") shouldBe true
                parseCallCount shouldBe 1
            }
        }
    })
