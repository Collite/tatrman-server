package org.tatrman.kantheon.testkit.sql

import java.sql.Connection

/**
 * Minimal SQL seed-script runner for the component tier. Splits a script on
 * `GO` batch separators (sqlcmd style — JDBC cannot execute a `GO`) and runs
 * each non-empty batch. Driver-agnostic: it takes a plain [java.sql.Connection],
 * so the testkit pulls in no JDBC driver itself.
 */
object SqlScripts {
    private val goSeparator = Regex("(?im)^\\s*GO\\s*$")

    /** Execute every `GO`-separated batch in [script] against [connection]. */
    fun run(
        connection: Connection,
        script: String,
    ) {
        script
            .split(goSeparator)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { batch ->
                connection.createStatement().use { it.execute(batch) }
            }
    }

    /** Load a seed script from a classpath resource and [run] it. */
    fun runResource(
        connection: Connection,
        resourcePath: String,
    ) {
        val script =
            SqlScripts::class.java.classLoader
                .getResourceAsStream(resourcePath)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("SQL seed resource not found on classpath: $resourcePath")
        run(connection, script)
    }
}
