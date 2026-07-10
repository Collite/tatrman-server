package org.tatrman.health.checker

import org.tatrman.health.service.HealthCheckResult
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

class TcpHealthChecker(
    override val technology: String,
    private val host: String,
    private val port: Int,
    private val timeout: Long = 3000,
    private val probe: TcpProbe? = null,
) : HealthChecker {
    interface TcpProbe {
        fun createProbeBytes(): ByteArray

        fun isValidResponse(response: ByteArray): Boolean
    }

    override suspend fun check(): HealthCheckResult =
        withTimeoutOrNull(timeout) {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeout.toInt())
                socket.soTimeout = timeout.toInt()
                val probeBytes = probe?.createProbeBytes()
                if (probeBytes != null) {
                    socket.outputStream.write(probeBytes)
                    socket.outputStream.flush()
                    val response = ByteArray(1024).also { socket.inputStream.read(it) }
                    if (probe.isValidResponse(response)) {
                        HealthCheckResult.healthy(
                            technology = technology,
                            source = "tcp",
                            details = mapOf("host" to host, "port" to port.toString()),
                        )
                    } else {
                        HealthCheckResult.unhealthy(
                            technology = technology,
                            source = "tcp",
                            error = "Invalid probe response",
                            details = mapOf("host" to host, "port" to port.toString()),
                        )
                    }
                } else {
                    HealthCheckResult.healthy(
                        technology = technology,
                        source = "tcp",
                        details = mapOf("host" to host, "port" to port.toString()),
                    )
                }
            }
        } ?: HealthCheckResult.unhealthy(
            technology = technology,
            source = "tcp",
            error = "Connection timeout",
            details = mapOf("host" to host, "port" to port.toString()),
        )
}

class RedisPingProbe : TcpHealthChecker.TcpProbe {
    override fun createProbeBytes(): ByteArray = "+PING\r\n".toByteArray()

    override fun isValidResponse(response: ByteArray): Boolean {
        val text = response.toString(Charsets.UTF_8)
        return text.contains("+PONG") || text.contains("PONG")
    }
}

class PostgresReadyProbe : TcpHealthChecker.TcpProbe {
    override fun createProbeBytes(): ByteArray = "".toByteArray()

    override fun isValidResponse(response: ByteArray): Boolean = true
}

class MssqlProbe : TcpHealthChecker.TcpProbe {
    override fun createProbeBytes(): ByteArray = byteArrayOf()

    override fun isValidResponse(response: ByteArray): Boolean = true
}
