// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.sql.DriverManager

/**
 * `core_catalog_default_theme_and_presets` (contract ADR 0007): a catalog version whose templates
 * inherit their theme takes the tenant default it rendered with as its own default theme, a version
 * without such templates does not, and each version declares the presets its themes style, named by
 * their labels.
 */
@Tag("integration")
class CatalogDefaultThemeMigrationTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `inheriting templates keep their theme through the catalog, and themes' presets are declared`() {
        val postgres = TestRuntimeLifecycle.postgres()
        val database = "catalog_default_theme_migration"
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
            // The state before the migration; its columns do not exist yet, so no command can make it.
            connection.createStatement().use {
                it.execute(
                    """
                    INSERT INTO tenants (id, name) VALUES ('acme', 'Acme');
                    INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type) VALUES
                        ('letters', 'acme', 'exchange.epistola.app', 'acme', 'Letters', 'AUTHORED'),
                        ('forms', 'acme', 'exchange.epistola.app', 'acme', 'Forms', 'AUTHORED');
                    INSERT INTO catalog_versions (tenant_key, catalog_id, kind, is_next)
                        SELECT 'acme', catalog_id, 'draft', true FROM catalogs;
                    INSERT INTO themes (id, tenant_key, name, block_style_presets, catalog_id, version_id)
                        SELECT 'brand', 'acme', 'Brand',
                               '{"muted": {"label": "Muted text", "styles": {"color": "#666"}}, "accent": {"styles": {}}}',
                               c.catalog_id, v.version_id
                        FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id WHERE c.catalog_key = 'letters';
                    UPDATE tenants SET default_theme_resource_id = (SELECT resource_id FROM themes WHERE id = 'brand');
                    INSERT INTO document_templates (id, tenant_key, name, catalog_id, version_id)
                        SELECT 'invoice', 'acme', 'Invoice', c.catalog_id, v.version_id
                        FROM catalogs c JOIN catalog_versions v ON v.catalog_id = c.catalog_id WHERE c.catalog_key = 'letters';
                    """.trimIndent(),
                )
            }
        }
        flyway(null).migrate()

        val rows = DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT c.catalog_key, r.resource_key, v.presets::text
                    FROM catalog_versions v
                    JOIN catalogs c ON c.catalog_id = v.catalog_id
                    LEFT JOIN catalog_resources r ON r.tenant_key = v.tenant_key AND r.resource_id = v.default_theme_resource_id
                    """.trimIndent(),
                ).use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2) to mapper.readTree(rs.getString(3))) } }
            }
        }

        assertThat(rows.getValue("letters").first).`as`("its template inherits its theme").isEqualTo("brand")
        assertThat(rows.getValue("forms").first).`as`("no template inherits there").isNull()
        assertThat(rows.getValue("letters").second).isEqualTo(
            mapper.readTree("""[{"key": "accent", "name": "accent", "description": null}, {"key": "muted", "name": "Muted text", "description": null}]"""),
        )
        assertThat(rows.getValue("forms").second).isEqualTo(mapper.readTree("[]"))
    }

    private companion object {
        /** The last migration before `core_catalog_default_theme_and_presets`. */
        const val BEFORE = "20261019055841"
    }
}
