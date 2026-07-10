package org.tatrman.fuzzy.loader

import io.grpc.Channel
import org.slf4j.LoggerFactory
import org.tatrman.meta.v1.AriadneServiceGrpc
import org.tatrman.meta.v1.DbTableDetail
import org.tatrman.meta.v1.GetObjectRequest
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.meta.v1.PageRequest
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.parseSchemaCode
import java.util.concurrent.TimeUnit

data class FuzzyTarget(
    val qname: QualifiedName,
    val tableQname: QualifiedName,
    val localName: String,
)

/**
 * Thin gRPC client over Ariadne (`AriadneService`, the metadata service) for the
 * fuzzy loader. The channel is owned by the caller (`Application.module`); this
 * class never builds or shuts it.
 *
 * Timeouts are enforced via gRPC's `withDeadlineAfter(...)` per call — the
 * deadline propagates on the wire and cancels the RPC server-side, unlike a
 * `kotlinx.coroutines.withTimeout` wrapper around a blocking call (which only
 * cancels the coroutine, not the underlying blocking I/O).
 *
 * Forked from ai-platform `fuzzy-matcher.MetadataServiceClient` (2026-06-14):
 * `com.tatrman.metadata.v1` → `org.tatrman.meta.v1`, `MetadataServiceGrpc`
 * → `AriadneServiceGrpc`. The wire calls (`ListObjects` fuzzy-only + `GetObject`)
 * are unchanged.
 */
class MetadataServiceClient(
    channel: Channel,
    private val schema: String,
    private val timeoutMs: Long,
) {
    private val logger = LoggerFactory.getLogger(MetadataServiceClient::class.java)

    private val stub: AriadneServiceGrpc.AriadneServiceBlockingStub =
        AriadneServiceGrpc.newBlockingStub(channel)

    private fun deadlined(): AriadneServiceGrpc.AriadneServiceBlockingStub =
        stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)

    fun listFuzzyColumns(): List<FuzzyTarget> {
        val schemaCode =
            parseSchemaCode(schema)
                ?: error("Unknown schema code in echo.metadata.schema: '$schema' (expected db|er|cnc)")

        val baseRequest =
            ListObjectsRequest
                .newBuilder()
                .setSchema(schemaCode)
                .setKind("column")
                .setFuzzyOnly(true)
                .build()

        val allTargets = mutableListOf<FuzzyTarget>()
        var pageToken: String? = null

        do {
            val pageRequestBuilder = PageRequest.newBuilder().setPageSize(100)
            pageToken?.let { pageRequestBuilder.setPageToken(it) }

            val reqWithPage = baseRequest.toBuilder().setPage(pageRequestBuilder.build()).build()
            val resp = deadlined().listObjects(reqWithPage)

            for (item in resp.itemsList) {
                if (item.kind != "column") continue
                val qname = item.qualifiedName
                allTargets.add(
                    FuzzyTarget(
                        qname = qname,
                        tableQname = tableQnameOf(qname),
                        localName = item.localName,
                    ),
                )
            }

            pageToken = resp.pageInfo.nextPageToken
        } while (pageToken.isNotEmpty())

        return allTargets
    }

    /**
     * Derives the table qname from a column qname structurally — column qnames are
     * shaped `<table-local-name>.<column-local-name>` inside the schema's namespace.
     * Saves one `GetObject` RPC per column compared with asking the metadata service.
     */
    private fun tableQnameOf(columnQname: QualifiedName): QualifiedName {
        val lastDot = columnQname.name.lastIndexOf('.')
        require(lastDot > 0) { "Column qname has no table component: ${columnQname.name}" }
        return columnQname.toBuilder().setName(columnQname.name.substring(0, lastDot)).build()
    }

    fun getTableDetail(tableQname: QualifiedName): DbTableDetail {
        val req = GetObjectRequest.newBuilder().setQualifiedName(tableQname).build()
        return deadlined().getObject(req).table
    }
}
