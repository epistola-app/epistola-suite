// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration.preflight

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The 1.x-to-2.0 preflight checks on a 1.x database: what would leave catalogs without the first
 * release the upgrade gives them is found before a single migration runs. The state is what 1.x
 * had; no 2.0 command can make it, so it is written as 1.x wrote it.
 */
@Tag("integration")
class OneXCatalogChecksTest {

    @Test
    fun `catalogs that use each other are a cycle, named with what they use`() {
        val report = oneX("preflight_cycle") {
            catalog("letters")
            catalog("brand")
            // letters' template uses brand's theme; brand's stencil uses letters' image.
            exec("INSERT INTO themes (id, tenant_key, catalog_key, name) VALUES ('house', 'acme', 'brand', 'House')")
            template("letters", "invoice", themeOf = "house")
            stencil("brand", "footer", """{"nodes": {"logo": {"type": "image", "props": {"assetId": "logo", "catalogKey": "letters"}}}}""")
        }

        assertThat(report.problems).singleElement().satisfies({ (check, problem) ->
            assertThat(check).isEqualTo(CatalogDependencyCycleCheck)
            assertThat(problem.tenantKey).isEqualTo("acme")
            assertThat(problem.summary).isEqualTo("catalogs 'brand', 'letters' use each other, which 2.0 does not allow.")
            assertThat(problem.details).containsExactly(
                "stencil footer in 'brand' uses 'letters' (content)",
                "template invoice in 'letters' uses 'brand' (theme house)",
            )
        })
        assertThat(report.describe()).contains("[1x-catalog-dependency-cycles, 1.x to 2.0] Tenant 'acme': catalogs").contains("Nothing was changed")
    }

    @Test
    fun `a catalog the tenant does not have is reported, and one-way use passes`() {
        val report = oneX("preflight_missing") {
            catalog("letters")
            catalog("brand")
            exec("INSERT INTO themes (id, tenant_key, catalog_key, name) VALUES ('house', 'acme', 'brand', 'House')")
            template("letters", "invoice", themeOf = "house", model = """{"nodes": {"n": {"type": "stencil", "props": {"stencilId": "x", "catalogKey": "ghost"}}}}""")
        }

        assertThat(report.problems).singleElement().satisfies({ (check, problem) ->
            assertThat(check).isEqualTo(MissingCatalogCheck)
            assertThat(problem.summary).isEqualTo("catalog 'letters' uses catalog 'ghost', which this tenant does not have.")
        })
    }

    @Test
    fun `only what the upgrade keeps counts, and a subscribed catalog waits for nothing`() {
        val report = oneX("preflight_kept") {
            catalog("letters")
            catalog("brand")
            catalog("vendor", type = "SUBSCRIBED")
            // An archived version that used brand is dropped by the upgrade; the draft does not use it.
            template("letters", "invoice", model = """{"nodes": {"n": {"type": "stencil", "props": {"catalogKey": "brand"}}}}""", status = "archived")
            exec(
                """
                INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status)
                SELECT 2, 'acme', resource_id, 'main', '{}', 'draft' FROM document_templates WHERE id = 'invoice'
                """,
            )
            stencil("brand", "footer", """{"nodes": {"n": {"type": "image", "props": {"catalogKey": "letters"}}}}""")
            // vendor and brand use each other, but vendor is subscribed: its release is recorded as it is.
            stencil("vendor", "badge", """{"nodes": {"n": {"type": "image", "props": {"catalogKey": "brand"}}}}""")
            stencil("brand", "frame", """{"nodes": {"n": {"type": "stencil", "props": {"catalogKey": "vendor"}}}}""")
        }

        assertThat(report.passed).isTrue()
    }

    @Test
    fun `a 2_0 database is not what the 1_x checks are about`() {
        val url = database("preflight_head")
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load().migrate()

        val report = DriverManager.getConnection(url, user, password).use { UpgradePreflights.run(it) }

        assertThat(report.outcomes).allMatch { it is UpgradePreflightOutcome.NotApplicable }
        assertThat(report.passed).isTrue()
    }

    // ── A 1.x database ───────────────────────────────────────────────────────

    private val postgres = TestRuntimeLifecycle.postgres()
    private val user = postgres.username
    private val password = postgres.password

    private fun database(name: String): String {
        DriverManager.getConnection(postgres.jdbcUrl, user, password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$name\"")
                it.execute("CREATE DATABASE \"$name\"")
            }
        }
        return postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$name$1")
    }

    private class OneX(val connection: Connection) {
        fun exec(sql: String) = connection.createStatement().use { it.execute(sql.trimIndent()) }

        fun catalog(key: String, type: String = "AUTHORED") = exec("INSERT INTO catalogs (id, tenant_key, name, type) VALUES ('$key', 'acme', '$key', '$type')")

        fun template(catalog: String, key: String, themeOf: String? = null, model: String = "{}", status: String = "published") {
            exec("INSERT INTO document_templates (id, tenant_key, catalog_key, name) VALUES ('$key', 'acme', '$catalog', '$key')")
            themeOf?.let { exec("UPDATE document_templates SET theme_resource_id = (SELECT resource_id FROM themes WHERE id = '$it') WHERE id = '$key'") }
            exec("INSERT INTO template_variants (id, tenant_key, template_resource_id, title, is_default) SELECT 'main', 'acme', resource_id, 'Main', TRUE FROM document_templates WHERE id = '$key'")
            exec(
                """
                INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status, published_at, archived_at)
                SELECT 1, 'acme', resource_id, 'main', '$model', '$status', NOW(), CASE WHEN '$status' = 'archived' THEN NOW() END
                FROM document_templates WHERE id = '$key'
                """,
            )
        }

        fun stencil(catalog: String, key: String, content: String) {
            exec("INSERT INTO stencils (id, tenant_key, catalog_key, name) VALUES ('$key', 'acme', '$catalog', '$key')")
            exec(
                """
                INSERT INTO stencil_versions (id, tenant_key, stencil_resource_id, content, status, published_at)
                SELECT 1, 'acme', resource_id, '$content', 'published', NOW() FROM stencils WHERE id = '$key' AND catalog_key = '$catalog'
                """,
            )
        }
    }

    private fun oneX(name: String, seed: OneX.() -> Unit): UpgradePreflightReport {
        val url = database(name)
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").target(LAST_1X).load().migrate()
        return DriverManager.getConnection(url, user, password).use { connection ->
            OneX(connection).apply { exec("INSERT INTO tenants (id, name) VALUES ('acme', 'Acme')") }.seed()
            val report = UpgradePreflights.run(connection)
            assertThat(report.outcomes).`as`("every 1.x check ran").allMatch { it is UpgradePreflightOutcome.Ran }
            report
        }
    }

    private companion object {
        /** The last migration 1.x shipped (1.5.0). */
        const val LAST_1X = "20260923150918"
    }
}
