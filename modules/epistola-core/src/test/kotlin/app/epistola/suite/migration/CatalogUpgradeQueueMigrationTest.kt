// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * `core_catalog_upgrade_queue` queues every authored catalog that exists at the upgrade, and every
 * subscribed one with an installed release, which still has to be recorded as a release it can
 * render from. A subscribed catalog that never installed one has nothing to record.
 */
@Tag("integration")
class CatalogUpgradeQueueMigrationTest {

    @Test
    fun `the authored catalogs and the installed subscribed ones are queued`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "catalog_upgrade_queue_migration"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$database\"")
                it.execute("CREATE DATABASE \"$database\"")
            }
        }
        val url = postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$database$1")
        fun flyway(target: String?) = Flyway.configure()
            .dataSource(url, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .apply { if (target != null) target(target) }
            .load()

        flyway(BEFORE).migrate()
        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            // Catalogs as an installation has them when it upgrades; no command of today writes that schema.
            connection.createStatement().use {
                it.execute(
                    """
                    INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                    INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type)
                    VALUES ('default', 'acme', 'exchange.epistola.app', 'acme', 'Default', 'AUTHORED'),
                           ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters', 'AUTHORED'),
                           ('system', 'acme', 'exchange.epistola.app', 'epistola', 'System', 'SUBSCRIBED');
                    INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type, installed_release_version)
                    VALUES ('forms', 'acme', 'exchange.epistola.app', 'acme', 'Forms', 'SUBSCRIBED', '3.1.0');
                    """.trimIndent(),
                )
            }
        }
        flyway(null).migrate()

        DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            val queued = connection.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT c.catalog_key, q.installed_version FROM catalog_upgrade_queue q
                    JOIN catalogs c ON c.tenant_key = q.tenant_key AND c.catalog_id = q.catalog_id
                    """,
                ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
            }
            // A subscribed one carries its installed release to the runner, which records it.
            assertThat(queued).containsExactlyInAnyOrder("default" to null, "letters" to null, "forms" to "3.1.0")
        }
    }

    private companion object {
        /** The migration before `core_catalog_upgrade_queue`. */
        const val BEFORE = "20261018201300"
    }
}
