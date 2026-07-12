// SPDX-License-Identifier: Apache-2.0
package shared.libs.db.common

import com.typesafe.config.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

class DatabaseConnection(
    private val dbConfig: Config,
) {
    private var dataSource: HikariDataSource? = null
    private val logger = LoggerFactory.getLogger("DatabaseConnection")

    fun init() {
        if (dataSource != null) return

        val dbType = dbConfig.getString("type").uppercase()

        val host = dbConfig.getString("host")
        val port = dbConfig.getString("port").toInt()
        val database = dbConfig.getString("database")
        val dbUser = dbConfig.getString("user")
        val dbPassword = dbConfig.getString("password")

        val hikariConfig =
            HikariConfig().apply {
                when (dbType) {
                    "MSSQL" -> {
                        driverClassName = "com.microsoft.sqlserver.jdbc.SQLServerDriver"
                        jdbcUrl =
                            "jdbc:sqlserver://$host:$port;" +
                            "databaseName=$database;encrypt=true;trustServerCertificate=true;"
                    }
                    "POSTGRES" -> {
                        driverClassName = "org.postgresql.Driver"
                        jdbcUrl = "jdbc:postgresql://$host:$port/$database"
                    }
                    else -> throw IllegalArgumentException("Unknown database type: $dbType")
                }
                username = dbUser
                password = dbPassword
                maximumPoolSize = 3
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                validate()
            }
        dataSource = HikariDataSource(hikariConfig)
        // Configure Exposed to wait 100 ms between transaction retry attempts
        Database.connect(
            dataSource!!,
            databaseConfig =
                DatabaseConfig {
                    defaultMinRetryDelay = 100L
                },
        )
        logger.info("DatabaseConnection initialized: {}", dbType)
    }

    fun getConnection() =
        dataSource?.connection
            ?: throw IllegalStateException("Database not initialized. Call init() first.")

    fun getDataSource(): javax.sql.DataSource =
        dataSource
            ?: throw IllegalStateException("Database not initialized. Call init() first.")

    fun <T> query(block: () -> T): T = transaction { block() }

    fun close() {
        dataSource?.close()
        dataSource = null
    }

    companion object {
        fun fromConfig(
            config: Config,
            path: String,
        ): DatabaseConnection {
            val dbConfig = config.getConfig(path)
            return DatabaseConnection(dbConfig)
        }
    }
}
