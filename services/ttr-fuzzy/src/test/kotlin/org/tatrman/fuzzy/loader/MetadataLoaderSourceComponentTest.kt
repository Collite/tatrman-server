package org.tatrman.fuzzy.loader

import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.tatrman.meta.v1.VelesServiceGrpc
import org.tatrman.meta.v1.DbTableDetail
import org.tatrman.meta.v1.GetObjectRequest
import org.tatrman.meta.v1.GetObjectResponse
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.meta.v1.ListObjectsResponse
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.PageInfo
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.PostgresConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.telemetry.EchoTelemetry
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end component test for the metadata-driven loader path (re-forked
 * 2026-06-14). Exercises the real [MetadataServiceClient] over `io.grpc.inprocess`
 * against a hand-rolled stub `VelesService`. Asserts on data flow (returned
 * maps, captured requests) — not just call counts.
 */
class MetadataLoaderSourceComponentTest :
    StringSpec({

        fun columnQn(
            namespace: String,
            name: String,
        ): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace(namespace)
                .setName(name)
                .build()

        fun columnDescriptor(qn: QualifiedName): ObjectDescriptor =
            ObjectDescriptor
                .newBuilder()
                .setQualifiedName(qn)
                .setKind("column")
                .setLocalName(qn.name.substringAfterLast('.'))
                .build()

        fun tableResponse(primaryKey: List<String>): GetObjectResponse =
            GetObjectResponse
                .newBuilder()
                .setTable(DbTableDetail.newBuilder().addAllPrimaryKey(primaryKey).build())
                .build()

        fun listResponse(
            descriptors: List<ObjectDescriptor>,
            nextPageToken: String = "",
        ): ListObjectsResponse =
            ListObjectsResponse
                .newBuilder()
                .addAllItems(descriptors)
                .setPageInfo(PageInfo.newBuilder().setNextPageToken(nextPageToken).build())
                .build()

        class StubVelesService : VelesServiceGrpc.VelesServiceImplBase() {
            var listObjectsResponses: List<ListObjectsResponse> = emptyList()
            var getObjectResponses: Map<QualifiedName, GetObjectResponse> = emptyMap()
            var listObjectsError: Status? = null
            var listObjectsDelayMs: Long = 0

            val capturedListObjects: ConcurrentLinkedQueue<ListObjectsRequest> = ConcurrentLinkedQueue()
            val capturedGetObjects: ConcurrentLinkedQueue<GetObjectRequest> = ConcurrentLinkedQueue()

            private var listCallIndex = 0

            override fun listObjects(
                request: ListObjectsRequest,
                responseObserver: StreamObserver<ListObjectsResponse>,
            ) {
                capturedListObjects.add(request)
                if (listObjectsDelayMs > 0) Thread.sleep(listObjectsDelayMs)
                val err = listObjectsError
                if (err != null) {
                    responseObserver.onError(err.asRuntimeException())
                    return
                }
                val responses = listObjectsResponses
                val resp = responses.getOrElse(listCallIndex) { responses.lastOrNull() ?: listResponse(emptyList()) }
                listCallIndex++
                responseObserver.onNext(resp)
                responseObserver.onCompleted()
            }

            override fun getObject(
                request: GetObjectRequest,
                responseObserver: StreamObserver<GetObjectResponse>,
            ) {
                capturedGetObjects.add(request)
                val resp =
                    getObjectResponses[request.qualifiedName]
                        ?: GetObjectResponse.newBuilder().setTable(DbTableDetail.getDefaultInstance()).build()
                responseObserver.onNext(resp)
                responseObserver.onCompleted()
            }
        }

        class TestHarness(
            val stub: StubVelesService,
            val server: Server,
            val channel: ManagedChannel,
            val client: MetadataServiceClient,
        ) {
            fun shutdown() {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }

        fun harness(timeoutMs: Long = 2_000): TestHarness {
            val name = "echo-loader-it-${UUID.randomUUID()}"
            val stub = StubVelesService()
            val server =
                InProcessServerBuilder
                    .forName(name)
                    .addService(stub)
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName(name).usePlaintext().build()
            val client = MetadataServiceClient(channel, schema = "db", timeoutMs = timeoutMs)
            return TestHarness(stub, server, channel, client)
        }

        fun captureFetch(
            fetched: MutableList<String>,
            perSqlRows: (String) -> List<Candidate> = { _ -> listOf(Candidate.fromValues("1", "Acme")) },
        ): (String) -> List<Candidate> =
            { sql ->
                fetched.add(sql)
                perSqlRows(sql)
            }

        "case 1 — happy path, single page, returns expected map and composes per-table SQL" {
            val h = harness()
            try {
                val customers = columnQn("dbo", "customers.name")
                val orders = columnQn("dbo", "orders.code")
                val customersTable = columnQn("dbo", "customers")
                val ordersTable = columnQn("dbo", "orders")

                h.stub.listObjectsResponses =
                    listOf(listResponse(listOf(columnDescriptor(customers), columnDescriptor(orders))))
                h.stub.getObjectResponses =
                    mapOf(
                        customersTable to tableResponse(listOf("id")),
                        ordersTable to tableResponse(listOf("id")),
                    )

                val fetched = mutableListOf<String>()
                val loader =
                    MetadataLoaderSource(
                        client = h.client,
                        dialect = PostgresConfig("h", 1, "db", "u", "p"),
                        sourceNamespace = "",
                        fetchCandidates = captureFetch(fetched),
                    )

                val result = runBlocking { loader.loadNextCache() }!!

                result.keys shouldContainExactlyInAnyOrder listOf("db.dbo.customers.name", "db.dbo.orders.code")
                fetched.any { it.contains("customers") } shouldBe true
                fetched.any { it.contains("orders") } shouldBe true
                h.stub.capturedListObjects.size shouldBe 1
                h.stub.capturedGetObjects.size shouldBe 2
            } finally {
                h.shutdown()
            }
        }

        "case 2 — pagination consumed across two pages" {
            val h = harness()
            try {
                val a = columnQn("dbo", "customers.name")
                val b = columnQn("dbo", "orders.code")
                h.stub.listObjectsResponses =
                    listOf(
                        listResponse(listOf(columnDescriptor(a)), nextPageToken = "p2"),
                        listResponse(listOf(columnDescriptor(b)), nextPageToken = ""),
                    )
                h.stub.getObjectResponses =
                    mapOf(
                        columnQn("dbo", "customers") to tableResponse(listOf("id")),
                        columnQn("dbo", "orders") to tableResponse(listOf("id")),
                    )

                val loader =
                    MetadataLoaderSource(
                        client = h.client,
                        dialect = PostgresConfig("h", 1, "db", "u", "p"),
                        sourceNamespace = "",
                        fetchCandidates = { _ -> listOf(Candidate.fromValues("1", "x")) },
                    )

                val result = runBlocking { loader.loadNextCache() }!!

                result.keys shouldContainExactlyInAnyOrder listOf("db.dbo.customers.name", "db.dbo.orders.code")
                h.stub.capturedListObjects.size shouldBe 2
                h.stub.capturedListObjects
                    .last()
                    .page.pageToken shouldBe "p2"
            } finally {
                h.shutdown()
            }
        }

        "case 3 — column whose table has empty primary_key is skipped (no_pk)" {
            val h = harness()
            try {
                val col = columnQn("dbo", "logs.line")
                h.stub.listObjectsResponses = listOf(listResponse(listOf(columnDescriptor(col))))
                h.stub.getObjectResponses = mapOf(columnQn("dbo", "logs") to tableResponse(emptyList()))

                val fetched = mutableListOf<String>()
                val loader =
                    MetadataLoaderSource(
                        client = h.client,
                        dialect = PostgresConfig("h", 1, "db", "u", "p"),
                        sourceNamespace = "",
                        fetchCandidates = captureFetch(fetched),
                    )

                val result = runBlocking { loader.loadNextCache() }!!

                result.shouldNotContainKey("db.dbo.logs.line")
                fetched.shouldBeEmpty()
            } finally {
                h.shutdown()
            }
        }

        "case 4 — composite primary key is skipped (composite_pk)" {
            val h = harness()
            try {
                val col = columnQn("dbo", "order_lines.note")
                h.stub.listObjectsResponses = listOf(listResponse(listOf(columnDescriptor(col))))
                h.stub.getObjectResponses =
                    mapOf(columnQn("dbo", "order_lines") to tableResponse(listOf("order_id", "line_no")))

                val fetched = mutableListOf<String>()
                val loader =
                    MetadataLoaderSource(
                        client = h.client,
                        dialect = PostgresConfig("h", 1, "db", "u", "p"),
                        sourceNamespace = "",
                        fetchCandidates = captureFetch(fetched),
                    )

                val result = runBlocking { loader.loadNextCache() }!!

                result.shouldNotContainKey("db.dbo.order_lines.note")
                fetched.shouldBeEmpty()
            } finally {
                h.shutdown()
            }
        }

        "case 5 — column outside configured sourceNamespace is skipped (wrong_source)" {
            val h = harness()
            try {
                val inside = columnQn("dbo", "customers.name")
                val outside = columnQn("warehouse", "shipments.tracking")
                val outsideTable = columnQn("warehouse", "shipments")

                h.stub.listObjectsResponses =
                    listOf(listResponse(listOf(columnDescriptor(inside), columnDescriptor(outside))))
                h.stub.getObjectResponses =
                    mapOf(
                        columnQn("dbo", "customers") to tableResponse(listOf("id")),
                        outsideTable to tableResponse(listOf("id")),
                    )

                val loader =
                    MetadataLoaderSource(
                        client = h.client,
                        dialect = PostgresConfig("h", 1, "db", "u", "p"),
                        sourceNamespace = "dbo",
                        fetchCandidates = { _ -> listOf(Candidate.fromValues("1", "x")) },
                    )

                val result = runBlocking { loader.loadNextCache() }!!

                result shouldContainKey "db.dbo.customers.name"
                result shouldNotContainKey "db.warehouse.shipments.tracking"
                h.stub.capturedGetObjects.any { it.qualifiedName == outsideTable } shouldBe false
            } finally {
                h.shutdown()
            }
        }

        "case 6 — fetchCandidates failure for one column is isolated (sql_failed)" {
            val h = harness()
            try {
                val good = columnQn("dbo", "customers.name")
                val bad = columnQn("dbo", "orders.code")
                h.stub.listObjectsResponses =
                    listOf(listResponse(listOf(columnDescriptor(good), columnDescriptor(bad))))
                h.stub.getObjectResponses =
                    mapOf(
                        columnQn("dbo", "customers") to tableResponse(listOf("id")),
                        columnQn("dbo", "orders") to tableResponse(listOf("id")),
                    )

                val loader =
                    MetadataLoaderSource(
                        client = h.client,
                        dialect = PostgresConfig("h", 1, "db", "u", "p"),
                        sourceNamespace = "",
                        fetchCandidates = { sql ->
                            if (sql.contains("orders")) error("synthetic JDBC failure")
                            listOf(Candidate.fromValues("1", "Acme"))
                        },
                    )

                val result = runBlocking { loader.loadNextCache() }!!

                result shouldContainKey "db.dbo.customers.name"
                result shouldNotContainKey "db.dbo.orders.code"
            } finally {
                h.shutdown()
            }
        }

        "case 7 — metadata listObjects failure returns null (preserve previous cache)" {
            val h = harness()
            try {
                h.stub.listObjectsError = Status.UNAVAILABLE.withDescription("simulated outage")

                val loader =
                    MetadataLoaderSource(
                        client = h.client,
                        dialect = PostgresConfig("h", 1, "db", "u", "p"),
                        sourceNamespace = "",
                        fetchCandidates = { _ -> listOf(Candidate.fromValues("1", "x")) },
                        telemetry = EchoTelemetry(),
                    )

                runBlocking { loader.loadNextCache() }.shouldBeNull()
            } finally {
                h.shutdown()
            }
        }

        "case 8 — atomic swap: a category dropped from the next refresh disappears from getCandidates" {
            class MutableLoader : LoaderSource {
                @Volatile
                var next: Map<String, List<Candidate>>? = null

                override suspend fun loadNextCache(): Map<String, List<Candidate>>? = next
            }

            val loader = MutableLoader()
            loader.next =
                mapOf(
                    "A" to listOf(Candidate.fromValues("1", "alpha")),
                    "B" to listOf(Candidate.fromValues("2", "bravo")),
                )

            val cfg =
                AppConfig(
                    serverPort = 7265,
                    grpcPort = 7266,
                    grpcReflectionEnabled = false,
                    refreshIntervalSeconds = 1,
                    loaderSource = LoaderSourceConfig(source = "metadata"),
                    metadata = MetadataConfig(),
                    database = PostgresConfig("h", 1, "db", "u", "p"),
                )

            val repo = StringRepository(cfg, loader, telemetry = null)
            try {
                eventually(5.seconds) {
                    repo.isCatalogReady() shouldBe true
                    repo.getCandidates("A").isEmpty() shouldBe false
                    repo.getCandidates("B").isEmpty() shouldBe false
                }
                loader.next = mapOf("A" to listOf(Candidate.fromValues("1", "alpha")))
                eventually(5.seconds) {
                    repo.getCandidates("B") shouldBe emptyList<Candidate>()
                }
                repo.getCandidates("A") shouldNotBe emptyList<Candidate>()
            } finally {
                repo.close()
            }
        }

        "case 9 — slow server triggers gRPC deadline; loader signals null within budget" {
            val timeoutMs = 200L
            val h = harness(timeoutMs = timeoutMs)
            try {
                h.stub.listObjectsDelayMs = timeoutMs * 3
                h.stub.listObjectsResponses = listOf(listResponse(emptyList()))

                val loader =
                    MetadataLoaderSource(
                        client = h.client,
                        dialect = PostgresConfig("h", 1, "db", "u", "p"),
                        sourceNamespace = "",
                        fetchCandidates = { _ -> emptyList() },
                    )

                val started = System.currentTimeMillis()
                val result = runBlocking { loader.loadNextCache() }
                val elapsedMs = System.currentTimeMillis() - started

                result.shouldBeNull()
                elapsedMs shouldBeLessThan (timeoutMs * 3)
            } finally {
                h.shutdown()
            }
        }
    })
