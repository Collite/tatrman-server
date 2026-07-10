package org.tatrman.kantheon.testkit.containers

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.mssqlserver.MSSQLServerContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * Pinned Testcontainers factories for the component tier (testing arc Stage 1.2).
 *
 * Every image tag is pinned here so specs never drift on `latest` and there is a
 * single place to bump them. Specs call these instead of re-declaring containers
 * (plan.md Stage 1.2 T3).
 */
object Containers {
    /** Native multi-arch — runs everywhere, including Apple Silicon. */
    const val POSTGRES_IMAGE: String = "postgres:16-alpine"

    /**
     * **amd64-only** — no native ARM64 image (Azure SQL Edge, the former ARM
     * fallback, was retired 2025-09-30). Specs that use it must be CI-gated with
     * [org.tatrman.kantheon.testkit.CiOnly]; see plan.md Stage 1.2 T3 + the Mac
     * Silicon caveat in testing architecture §9.
     */
    const val MSSQL_IMAGE: String = "mcr.microsoft.com/mssql/server:2022-latest"

    /** Native multi-arch. Matches the catalog `wiremock` version. */
    const val WIREMOCK_IMAGE: String = "wiremock/wiremock:3.13.2"

    /** The port WireMock serves both its stubs and its `/__admin` API on. */
    const val WIREMOCK_PORT: Int = 8080

    fun postgres(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))

    /**
     * MSSQL container with the EULA accepted and the image platform pinned to
     * `linux/amd64`, so the rare local opt-in run (`-DmssqlLocal`) is unambiguous
     * about emulating amd64 rather than failing to find an arm image. On the
     * amd64 CI runner the pin is a no-op (already native).
     */
    fun mssql(): MSSQLServerContainer =
        MSSQLServerContainer(DockerImageName.parse(MSSQL_IMAGE))
            .acceptLicense()
            .withCreateContainerCmdModifier { it.withPlatform("linux/amd64") }
            // Native amd64 boots in ~30s; this is headroom for a cold/slow CI
            // node (and for the rare `-DmssqlLocal` run under arm64 emulation,
            // where SQL Server takes minutes to accept connections).
            .withStartupTimeout(Duration.ofMinutes(5))

    /** Empty WireMock — fixtures are pushed at runtime via [org.tatrman.kantheon.testkit.wiremock.WireMockAdmin]. */
    fun wiremock(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse(WIREMOCK_IMAGE))
            .withExposedPorts(WIREMOCK_PORT)
            .waitingFor(Wait.forHttp("/__admin/mappings").forPort(WIREMOCK_PORT).forStatusCode(200))
}
