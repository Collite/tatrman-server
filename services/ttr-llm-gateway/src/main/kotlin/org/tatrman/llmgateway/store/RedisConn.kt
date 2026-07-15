// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.store

import com.typesafe.config.Config
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import io.lettuce.core.api.StatefulRedisConnection
import java.time.Duration

/**
 * LG-P1·S1·T4 — Redis wrapper (lettuce; this is the FIRST lettuce use in the repo — replaces 1.x
 * Spring `StringRedisTemplate`). Holds a lazily-opened, auto-reconnecting connection so a Redis
 * outage never fails boot — [probe] just reports the truth (F-1). 2 s connect + command timeout so a
 * dead upstream can't hang the readiness check. Rate-limit windows (D-4) and the response cache (E-1)
 * consume [sync] in later stages.
 */
class RedisConn private constructor(
    private val client: RedisClient,
) {
    @Volatile private var conn: StatefulRedisConnection<String, String>? = null

    private fun connection(): StatefulRedisConnection<String, String> =
        conn ?: synchronized(this) { conn ?: client.connect().also { conn = it } }

    /** Synchronous command API for consumers (INCR/EXPIRE, GET/SETEX). */
    fun sync() = connection().sync()

    /** Live reachability check — a real `PING`; resets the connection on failure so it re-dials next time. */
    fun probe(): Boolean =
        try {
            connection().sync().ping() == "PONG"
        } catch (e: Exception) {
            conn = null
            false
        }

    fun close() {
        conn?.close()
        client.shutdown()
    }

    companion object {
        /** Build (do not connect yet) from the `redis { … }` config block. */
        fun fromConfig(config: Config): RedisConn {
            val redis = config.getConfig("redis")
            val uri =
                RedisURI
                    .Builder
                    .redis(redis.getString("host"), redis.getInt("port"))
                    .withTimeout(Duration.ofSeconds(2))
                    .build()
            val client = RedisClient.create(uri)
            client.options =
                ClientOptions
                    .builder()
                    .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build())
                    .build()
            return RedisConn(client)
        }
    }
}
