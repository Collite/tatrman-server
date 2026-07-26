package cz.tatrman.charon.core

import cz.tatrman.secrets.spi.SecretMaterial
import cz.tatrman.secrets.spi.SecretRef
import cz.tatrman.secrets.spi.SecretStore
import cz.tatrman.secrets.spi.SecretStoreRegistry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * PL-P3.S1.T5 — Charon's connection acquisition wired to the secret-store SPI (contracts §17, H-5),
 * replacing the donor's `${ENV}` plaintext substitution. The connections YAML carries only non-secret
 * metadata + a `${TTR_CONN_*}` token for the credential; [ConnectionRegistry.fromSecrets] resolves that
 * token from the SPI (the [TransferSecretInjector]) and the resulting handle reaches a LIVE Postgres query.
 * The `TTR_CONN_*` env contract is verbatim the hall's. Live-k8s mount wiring is operate-layer (S6 defer).
 */
class ConnectionSecretsTest :
    StringSpec({

        // A distinct password (not the Testcontainers default "test", which collides with the db name /
        // username) so "the YAML carries no plaintext password" is a meaningful assertion.
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).withPassword("s3cr3t-canary-9f81")
        val provider = HikariConnectionProvider()

        beforeSpec {
            pg.start()
            val seed =
                ConnectionHandle(
                    id = "seed",
                    dialect = DbDialect.POSTGRES,
                    jdbcUrl = pg.jdbcUrl,
                    username = pg.username,
                    password = pg.password,
                    allow = AllowList(read = true, write = true, schemas = setOf("public")),
                    poolMax = 2,
                )
            provider.open(seed).use { conn ->
                conn.createStatement().use { st ->
                    st.execute("CREATE TABLE orders (id BIGINT)")
                    st.execute("INSERT INTO orders VALUES (1),(2),(3)")
                }
            }
        }

        afterSpec {
            provider.close()
            pg.stop()
        }

        "connection credentials resolve from the secret-store SPI (not plaintext config) and reach a live query" {
            // jdbc_url + username are non-secret config; the PASSWORD is a `${TTR_CONN_*}` token — never
            // inline in the YAML. The `$` is escaped so the token reaches the registry's substitution intact.
            val yaml =
                """
                connections:
                  - id: warehouse
                    kind: postgres
                    jdbc_url: "${pg.jdbcUrl}"
                    username: "${pg.username}"
                    password: "${'$'}{TTR_CONN_WAREHOUSE_PW}"
                    allow: { read: true, write: false, schemas: [public] }
                    pool: { max: 2 }
                """.trimIndent()

            // The store holds only the password — the sensitive part — keyed by the connection's ref.
            val store =
                object : SecretStore {
                    override val scheme = "k8s"

                    override fun resolve(ref: SecretRef) = SecretMaterial(pg.password.toByteArray())
                }
            val registry =
                ConnectionRegistry.fromSecrets(
                    yaml,
                    mapOf("TTR_CONN_WAREHOUSE_PW" to SecretRef.parse("secret://k8s/warehouse-pw")),
                    SecretStoreRegistry(listOf(store)),
                )

            // the YAML carried NO plaintext password — the credential came from the SPI.
            yaml shouldNotContain pg.password
            val handle = (registry.authorize("warehouse", DbOp.READ, "public") as Either.Right).value
            handle.password shouldBe pg.password

            provider.open(handle).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT count(*) FROM orders").use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 3
                    }
                }
            }
        }
    })
