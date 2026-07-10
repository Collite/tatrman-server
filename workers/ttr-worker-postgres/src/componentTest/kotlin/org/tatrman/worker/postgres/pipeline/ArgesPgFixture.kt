package org.tatrman.worker.postgres.pipeline

import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.util.UUID

/**
 * Shared component-test fixture: provisions a tiny RLS-protected schema on a Testcontainers
 * Postgres and seeds tenant-scoped rows, mirroring Midas's production RLS shape (V0001) in
 * miniature. The worker connects as the **non-owner** [READONLY_ROLE] so RLS policies actually
 * apply (the container's default user is a superuser and would bypass RLS).
 *
 * `app_current_tenant()` reads the `app.tenant_id` GUC and raises when unset — the fail-closed
 * contract Arges relies on. The seed runs as the superuser, which bypasses RLS, so inserts need
 * no GUC.
 */
object ArgesPgFixture {
    const val READONLY_ROLE: String = "midas_app_readonly"
    const val READONLY_PW: String = "ro_pw"

    /** The five-column table the worker reads — the v1 must-pass type set (bigint/numeric/varchar). */
    private const val DDL_FUNCTION =
        "CREATE OR REPLACE FUNCTION app_current_tenant() RETURNS uuid AS $$ " +
            "BEGIN RETURN current_setting('app.tenant_id')::uuid; " +
            "EXCEPTION WHEN OTHERS THEN RAISE EXCEPTION 'app.tenant_id session var not set'; " +
            "END; $$ LANGUAGE plpgsql STABLE"

    private const val DDL_TABLE =
        "CREATE TABLE positions (" +
            "tenant_id uuid NOT NULL, account_id bigint NOT NULL, " +
            "amount numeric(20,4) NOT NULL, label varchar(64) NOT NULL)"

    /** Run the full provisioning (function + table + RLS + read-only role) as the superuser. */
    fun provision(superuser: Connection) {
        superuser.createStatement().use { st ->
            st.execute(DDL_FUNCTION)
            st.execute(DDL_TABLE)
            st.execute("ALTER TABLE positions ENABLE ROW LEVEL SECURITY")
            st.execute("ALTER TABLE positions FORCE ROW LEVEL SECURITY")
            st.execute("CREATE POLICY positions_tenant ON positions USING (tenant_id = app_current_tenant())")
            // The non-owner login role Arges connects as — subject to RLS (not an owner/superuser).
            st.execute("CREATE ROLE $READONLY_ROLE LOGIN PASSWORD '$READONLY_PW' NOSUPERUSER")
            st.execute("GRANT USAGE ON SCHEMA public TO $READONLY_ROLE")
            st.execute("GRANT SELECT ON positions TO $READONLY_ROLE")
            st.execute("GRANT EXECUTE ON FUNCTION app_current_tenant() TO $READONLY_ROLE")
        }
    }

    /** Insert one row for [tenant] (as the superuser, which bypasses RLS — no GUC needed). */
    fun insert(
        superuser: Connection,
        tenant: UUID,
        accountId: Long,
        amount: String,
        label: String,
    ) {
        superuser
            .prepareStatement(
                "INSERT INTO positions (tenant_id, account_id, amount, label) VALUES (?, ?, ?::numeric, ?)",
            ).use { ps ->
                ps.setObject(1, tenant)
                ps.setLong(2, accountId)
                ps.setString(3, amount)
                ps.setString(4, label)
                ps.executeUpdate()
            }
    }

    /** A connection for [user] against [jdbcUrl]. */
    fun connect(
        jdbcUrl: String,
        user: String,
        password: String,
    ): Connection =
        PGSimpleDataSource()
            .apply {
                setURL(jdbcUrl)
                this.user = user
                this.password = password
            }.connection
}
