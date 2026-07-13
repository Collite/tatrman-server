package org.tatrman.geo.cache

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.tatrman.geo.resolve.Boundary
import org.tatrman.geo.resolve.ResolvedPlace
import shared.libs.db.common.DatabaseConnection
import java.time.Duration
import java.time.Instant

/**
 * A9.4 persistence — the durable Postgres boundary cache over a real Postgres (Testcontainers).
 * Covers the round-trip (WKT + bbox survive), refresh-on-read staleness, alias redirection, and the
 * centroid-only (no polygon) row.
 */
class PostgresBoundaryStoreSpec :
    StringSpec({
        val pg =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("geo")
                .withUsername("geo")
                .withPassword("geo")
                .also { it.start() }
        afterSpec { pg.stop() }

        val db =
            DatabaseConnection(
                ConfigFactory.parseString(
                    """
                    type = "POSTGRES"
                    host = "${pg.host}"
                    port = "${pg.firstMappedPort}"
                    database = "geo"
                    user = "geo"
                    password = "geo"
                    """.trimIndent(),
                ),
            ).also { it.init() }
        Flyway
            .configure()
            .dataSource(db.getDataSource())
            .load()
            .migrate()

        val brno =
            ResolvedPlace(
                "Brno, Jihomoravský kraj, Czechia",
                49.1951,
                16.6068,
                boundary =
                    Boundary(
                        "POLYGON ((16.5 49.1, 16.7 49.1, 16.7 49.3, 16.5 49.3, 16.5 49.1))",
                        49.1,
                        16.5,
                        49.3,
                        16.7,
                    ),
            )

        "put → get round-trips the place, boundary WKT + bbox, and OSM attribution" {
            val store = PostgresBoundaryStore(db)
            store.put("Brno", brno)

            val got = store.get("brno").shouldNotBeNull() // diacritic/case fold
            got.lat shouldBe (49.1951 plusOrMinus 1e-6)
            val b = got.boundary.shouldNotBeNull()
            b.wkt shouldContain "POLYGON"
            b.minLat shouldBe (49.1 plusOrMinus 1e-6)
            b.maxLon shouldBe (16.7 plusOrMinus 1e-6)

            // attribution is persisted for OSM sources
            db.query {
                BoundaryCacheTable
                    .selectAll()
                    .where { BoundaryCacheTable.placeRef eq "brno" }
                    .single()[BoundaryCacheTable.attribution]
            } shouldContain "OpenStreetMap"
        }

        "a stale entry (older than the TTL) reads as a miss → refresh-on-read" {
            val past = Instant.parse("2000-01-01T00:00:00Z")
            val store =
                PostgresBoundaryStore(db, ttl = Duration.ofDays(90), now = { past })
            store.put("Ostrava", brno.copy(label = "Ostrava"))

            // reading "now" makes the year-2000 write far older than the 90-day TTL
            PostgresBoundaryStore(db, ttl = Duration.ofDays(90), now = { Instant.now() })
                .get("Ostrava")
                .shouldBeNull()
        }

        "an alias redirects onto the canonical place_ref's boundary" {
            val store = PostgresBoundaryStore(db)
            store.put("Brno", brno)
            store.putAlias("Brna", "Brno") // cs genitive → canonical

            store.get("Brna").shouldNotBeNull().lat shouldBe (49.1951 plusOrMinus 1e-6)
        }

        "a centroid-only hit (no polygon) round-trips with a null boundary" {
            val store = PostgresBoundaryStore(db)
            store.put("Point Nemo", ResolvedPlace("Point Nemo", -48.876, -123.393, boundary = null))

            store
                .get("Point Nemo")
                .shouldNotBeNull()
                .boundary
                .shouldBeNull()
        }
    })
