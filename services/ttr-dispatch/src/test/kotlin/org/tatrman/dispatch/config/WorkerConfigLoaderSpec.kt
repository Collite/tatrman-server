// SPDX-License-Identifier: Apache-2.0
package org.tatrman.dispatch.config

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WorkerConfigLoaderSpec :
    StringSpec({

        "loads each declared worker slot with its role-hint" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    dispatch.workers = [
                        { endpoint = "workers-mssql:7401",  role-hint = "mssql" }
                        { endpoint = "workers-polars:7501", role-hint = "polars" }
                    ]
                    """.trimIndent(),
                )
            WorkerConfigLoader.load(cfg) shouldBe
                listOf(
                    WorkerSlot("workers-mssql:7401", "mssql"),
                    WorkerSlot("workers-polars:7501", "polars"),
                )
        }

        "skips entries with an empty endpoint (env-var unset)" {
            // Mirror the shape application.conf uses: optional substitution + a fallback empty.
            val cfg =
                ConfigFactory.parseString(
                    """
                    dispatch.workers = [
                        { endpoint = "", role-hint = "mssql" }
                        { endpoint = "workers-polars:7501", role-hint = "polars" }
                    ]
                    """.trimIndent(),
                )
            WorkerConfigLoader.load(cfg) shouldBe listOf(WorkerSlot("workers-polars:7501", "polars"))
        }

        "absent dispatch.workers → empty list" {
            WorkerConfigLoader.load(ConfigFactory.parseString("")) shouldBe emptyList()
        }

        "missing role-hint defaults to empty string" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    dispatch.workers = [
                        { endpoint = "workers-mssql:7401" }
                    ]
                    """.trimIndent(),
                )
            WorkerConfigLoader.load(cfg) shouldBe listOf(WorkerSlot("workers-mssql:7401", ""))
        }

        "trims whitespace around the endpoint" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    dispatch.workers = [
                        { endpoint = "  workers-mssql:7401  ", role-hint = "mssql" }
                    ]
                    """.trimIndent(),
                )
            WorkerConfigLoader.load(cfg) shouldBe listOf(WorkerSlot("workers-mssql:7401", "mssql"))
        }

        "the bundled application.conf parses cleanly (env-vars unset → no workers wired)" {
            val cfg = ConfigFactory.load()
            // No env vars set in test runtime → both slots skipped (their endpoint is empty).
            WorkerConfigLoader.load(cfg) shouldBe emptyList()
        }
    })
