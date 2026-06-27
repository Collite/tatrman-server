package org.tatrman.kantheon.kyklop.world

import com.typesafe.config.ConfigFactory
import org.tatrman.plan.v1.QualifiedName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WorldConfigSpec :
    StringSpec({
        val cfg =
            ConfigFactory.parseString(
                """
                world {
                  default-connection = "df-test"
                  table-connections {
                    "db.dbo.QHDOK_FAKTURY" = "df-test-fin-exact"
                    "db.dbo.QHDOK_*"       = "df-test-fin"
                    "db.dbo.QSUBJEKT*"     = "df-test-crm"
                  }
                }
                """.trimIndent(),
            )
        val world = WorldConfig.fromConfig(cfg)

        "exact match wins over prefix" {
            world.routingFor(qname("db.dbo.QHDOK_FAKTURY")) shouldBe "df-test-fin-exact"
        }

        "prefix match works" {
            world.routingFor(qname("db.dbo.QHDOK_OBJEDNAVKY")) shouldBe "df-test-fin"
        }

        "prefix match without underscore separator" {
            world.routingFor(qname("db.dbo.QSUBJEKT_KLIENT")) shouldBe "df-test-crm"
        }

        "fall back to null when no pattern matches" {
            world.routingFor(qname("db.dbo.QOTHER")) shouldBe null
        }

        "resolveOrDefault falls back to default-connection" {
            world.resolveOrDefault(qname("db.dbo.QOTHER")) shouldBe "df-test"
        }
    })

private fun qname(dotPath: String): QualifiedName {
    val parts = dotPath.split(".")
    return QualifiedName
        .newBuilder()
        .setSchemaCode(
            org.tatrman.plan.v1.SchemaCode
                .valueOf(parts[0].uppercase()),
        ).setNamespace(parts.getOrNull(1).orEmpty())
        .setName(parts.getOrNull(2).orEmpty())
        .build()
}
