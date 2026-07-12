// SPDX-License-Identifier: Apache-2.0
package org.tatrman.keycloak.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * Guards the Stage 5.0 extraction promise: keycloak-auth was lifted out of
 * `erp-sql-common.auth` precisely because those four files have **zero** imports
 * from the rest of erp-sql-common, so the legacy ERP-SQL line need not fork to
 * carry them. This spec fails if anyone re-introduces an erp-sql / infra.erp
 * coupling into the lib's main sources.
 */
class NoErpSqlCommonImportSpec :
    StringSpec({

        val forbidden = listOf("erp.sql.common", "erp_sql_common", "infra.erp", "com.tatrman")

        "keycloak-auth main sources carry no erp-sql-common / legacy coupling" {
            val mainSrc = File("src/main/kotlin")
            mainSrc.exists() shouldNotBe false

            val offenders =
                mainSrc
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file ->
                        file.readLines().mapIndexedNotNull { idx, line ->
                            val hit = forbidden.firstOrNull { line.contains(it) }
                            if (hit != null) "${file.name}:${idx + 1} -> $hit" else null
                        }
                    }.toList()

            offenders.shouldBeEmpty()
        }
    })
