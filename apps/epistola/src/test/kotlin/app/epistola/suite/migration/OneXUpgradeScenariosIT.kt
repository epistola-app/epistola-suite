// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.documents.NoReleaseDeployedException
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.environments.queries.PUBLISHED_IN_1X
import app.epistola.suite.generation.release.ReleaseTargetResolver
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
import app.epistola.suite.testing.TestRuntimeLifecycle
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap

/**
 * The 1.x-to-2.0 upgrade from the scenarios an installation brings with it, each in a tenant of its
 * own on one database 1.x left, which the app then starts on (Flyway with the preflight and the 2.0
 * migrations, then the boot passes), on every supported PostgreSQL major version:
 *
 * - two authored catalogs, one using the other's theme: both keep their next draft, the one with
 *   unpublished work gets a "Published in 1.x" draft beside it, and releasing pins the used catalog's
 *   release, refused while it has none;
 * - a catalog 1.x subscribed to from a URL: at boot it receives its source's release, and its next
 *   version fast-forwards to it;
 * - an environment that activated versions in 1.x: it starts with nothing deployed, its activations
 *   are kept in the history summary, a request through it is told nothing is deployed, and deploying
 *   the released "Published in 1.x" draft makes it render again.
 *
 * What a 1.x database on its last release keeps is [DataPreservationMigrationIT] and
 * [OneXUpgradeReleasesIT]; an unfinished 1.x content backfill is [LegacyContentMigrationTest] and the
 * resume of its migration [LegacyContentResumeTest].
 *
 * The 1.x rows are raw SQL on purpose: no command of today writes the 1.x schema.
 */
abstract class OneXUpgradeScenariosIT : BaseIntegrationTest() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var resolver: ReleaseTargetResolver

    @Test
    fun `two catalogs, one using the other, are released the used one first`() {
        val letters = testCatalog(DEPS, LETTERS)
        val shared = testCatalog(DEPS, SHARED)
        assertThat(drafts(DEPS, LETTERS)).`as`("letters keeps its next draft and gets what it published beside it").containsExactlyInAnyOrder(null, PUBLISHED_IN_1X)
        assertThat(drafts(DEPS, SHARED)).`as`("shared published all it has: no draft beside its next").containsExactly(null)
        assertThat(declaredByName(DEPS, LETTERS)).`as`("the upgrade declares, by name, what letters' content uses").contains(SHARED.value)
        val published = ReleaseCatalogDraft(letters, catalogVersion = CatalogReleaseRef(letters, DraftRef.Id(namedDraft(DEPS, LETTERS))))

        // In the wrong order, the release is refused, naming the catalog to release first.
        assertThatThrownBy { asTenant(DEPS) { published.execute() } }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("shared has no release to pin; release shared first")

        val sharedRelease = asTenant(DEPS) { ReleaseCatalogDraft(shared).execute() }
        val lettersRelease = asTenant(DEPS) { published.execute() }

        assertThat(lettersRelease.version).`as`("the patch after letters' 1.x release").isEqualTo("1.1.1")
        assertThat(lettersRelease.pinnedDependencies.map { "${it.catalogKey}@${it.releaseVersion}" }).contains("${SHARED.value}@${sharedRelease.version}")
    }

    @Test
    fun `a catalog subscribed to from a URL receives its source's release at boot`() {
        assertThat(count("SELECT count(*) FROM catalogs WHERE tenant_key = :t AND catalog_key = 'partner'", SUBSCRIBER))
            .`as`("the tenant's 1.x catalog receives it, renamed to the name its source gives it; no second catalog").isOne()
        val partner = CatalogId(PUBLISHER_NAMESPACE, PARTNER, TenantId(SUBSCRIBER))
        assertThat(releases(SUBSCRIBER, PARTNER)).`as`("received at boot from its source, as an installed release").containsExactly("1.1.0" to "installed")
        val theme = requireNotNull(asTenant(SUBSCRIBER) { GetTheme(ThemeId(ThemeKey.of("partner-theme"), partner)).query() })
        assertThat(theme.documentStyles["fontSize"]).`as`("its next version fast-forwarded to the received release").isEqualTo("12pt")
    }

    @Test
    fun `an environment that activated versions in 1x starts with nothing deployed, until its release is deployed`() {
        val letters = testCatalog(ENV, LETTERS)
        val production = EnvironmentKey.of("production")
        assertThat(count("SELECT count(*) FROM environments WHERE tenant_key = :t AND id = 'production'", ENV)).isOne()
        assertThat(count("SELECT count(*) FROM environment_catalog_deployments WHERE tenant_key = :t", ENV)).`as`("nothing is deployed after the upgrade").isZero()
        assertThat(count("SELECT count(*) FROM environment_deployment_log WHERE tenant_key = :t", ENV)).`as`("and the log claims no deployment either").isZero()
        assertThat(
            jdbi.withHandle<String, Exception> {
                it.createQuery("SELECT value::text FROM app_metadata WHERE key = 'upgrade-1x-history'").mapTo(String::class.java).one()
            },
        ).`as`("what the environment had activated is in the history summary").contains("\"environment\": \"production\"").contains("\"tenant\": \"${ENV.value}\"")

        assertThatThrownBy { asTenant(ENV) { resolver.resolveForGeneration(letters, INVOICE, null, null, production, null) } }
            .isInstanceOf(NoReleaseDeployedException::class.java)

        val release = asTenant(ENV) { ReleaseCatalogDraft(letters, catalogVersion = CatalogReleaseRef(letters, DraftRef.Id(namedDraft(ENV, LETTERS)))).execute() }
        asTenant(ENV) { DeployCatalogRelease(EnvironmentId(production, TenantId(ENV)), letters, release.version, includeDependencies = true).execute() }

        assertThat(asTenant(ENV) { resolver.resolveForGeneration(letters, INVOICE, null, null, production, null) }.releaseVersion).isEqualTo(release.version)
        assertThat(count("SELECT count(*) FROM environment_deployment_log WHERE tenant_key = :t AND action = 'DEPLOYED'", ENV)).isPositive()
    }

    /** The drafts of [catalog] in [tenant], by name: null for the unnamed next draft. */
    private fun drafts(tenant: TenantKey, catalog: CatalogKey): List<String?> = jdbi.withHandle<List<String?>, Exception> { handle ->
        handle.createQuery(
            "SELECT v.name FROM catalog_versions v JOIN catalogs c ON c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id WHERE v.tenant_key = :t AND c.catalog_key = :k AND v.state = 'draft'",
        ).bind("t", tenant).bind("k", catalog.value).map { rs, _ -> rs.getString(1) }.list()
    }

    private fun namedDraft(tenant: TenantKey, catalog: CatalogKey): String = jdbi.withHandle<String, Exception> { handle ->
        handle.createQuery(
            "SELECT v.version_key FROM catalog_versions v JOIN catalogs c ON c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id WHERE v.tenant_key = :t AND c.catalog_key = :k AND v.name = :n AND v.state = 'draft'",
        ).bind("t", tenant).bind("k", catalog.value).bind("n", PUBLISHED_IN_1X).mapTo(String::class.java).one()
    }

    private fun declaredByName(tenant: TenantKey, catalog: CatalogKey): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery(
            "SELECT d.dependency_key FROM catalog_version_dependencies d JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id WHERE d.tenant_key = :t AND c.catalog_key = :k AND c.next_version_id = d.version_id",
        ).bind("t", tenant).bind("k", catalog.value).mapTo(String::class.java).list()
    }

    private fun releases(tenant: TenantKey, catalog: CatalogKey): List<Pair<String, String>> = jdbi.withHandle<List<Pair<String, String>>, Exception> { handle ->
        handle.createQuery(
            "SELECT v.version, v.origin FROM catalog_versions v JOIN catalogs c ON c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id WHERE v.tenant_key = :t AND c.catalog_key = :k AND v.state = 'released' ORDER BY v.version",
        ).bind("t", tenant).bind("k", catalog.value).map { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
    }

    private fun count(sql: String, tenant: TenantKey): Int = jdbi.withHandle<Int, Exception> { it.createQuery(sql).bind("t", tenant).mapTo(Int::class.java).one() }

    private fun <T> asTenant(tenant: TenantKey, block: () -> T): T = SecurityContext.runWithPrincipal(SystemUser.principalForTenant(tenant)) { MediatorContext.runWithMediator(mediator) { block() } }

    companion object {
        /** The last migration 1.x shipped (1.4.0 and 1.5.0 both end on it). */
        private const val LAST_1X = "20260923150918"

        private val DEPS = TenantKey.of("acme-deps")
        private val SUBSCRIBER = TenantKey.of("acme-sub")
        private val ENV = TenantKey.of("acme-env")
        private val LETTERS = CatalogKey.of("letters")
        private val SHARED = CatalogKey.of("shared")
        private val PARTNER = CatalogKey.of("partner")
        private val INVOICE = TemplateKey.of("invoice")
        private const val PUBLISHER_NAMESPACE = "partner-co"

        /** Servers of a PostgreSQL major version the shared container does not run, one per JVM. */
        private val otherServers = ConcurrentHashMap<Int, PostgreSQLContainer>()

        private fun server(majorVersion: Int): PostgreSQLContainer = if (majorVersion == TestRuntimeLifecycle.postgresMajorVersion) {
            TestRuntimeLifecycle.postgres()
        } else {
            otherServers.computeIfAbsent(majorVersion) { TestRuntimeLifecycle.newPostgresContainer(it).also { container -> container.start() } }
        }

        /** A database 1.x left, seeded with every scenario, on a server of [majorVersion]. */
        fun oneXDatabase(majorVersion: Int): JdbcConnectionDetails {
            val postgres = server(majorVersion)
            val database = "onex_upgrade_scenarios_$majorVersion"
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
                admin.createStatement().use {
                    it.execute("DROP DATABASE IF EXISTS \"$database\"")
                    it.execute("CREATE DATABASE \"$database\"")
                }
            }
            val url = postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$database$1")
            Flyway.configure().dataSource(url, postgres.username, postgres.password).locations("classpath:db/migration").target(LAST_1X).load().migrate()
            val source = partnerSource(Files.createTempDirectory("onex-partner-$majorVersion"))
            DriverManager.getConnection(url, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { it.execute(seed(source)) }
                check(connection.metaData.databaseMajorVersion == majorVersion) { "seeded on PostgreSQL ${connection.metaData.databaseMajorVersion}, not $majorVersion" }
            }
            return object : JdbcConnectionDetails {
                override fun getUsername(): String = postgres.username
                override fun getPassword(): String = postgres.password
                override fun getJdbcUrl(): String = url
            }
        }

        /**
         * The source a 1.x tenant subscribed to `partner` from: a file catalog under the test's file
         * root, whose release (1.1.0) is newer than the one 1.x installed (1.0.0), with a theme that
         * says so. It names the catalog as its publisher does, in the publisher's namespace, not the
         * one the upgrade gave the tenant's copy (the tenant's key: 1.x catalogs had no namespace).
         */
        private fun partnerSource(dir: Path): String {
            Files.createDirectories(dir.resolve("resources/themes"))
            Files.writeString(
                dir.resolve("catalog.json"),
                """
                {
                  "schemaVersion": 8,
                  "catalog": {"key": "partner", "registry": "exchange.epistola.app", "namespace": "$PUBLISHER_NAMESPACE", "name": "Partner"},
                  "publisher": {"name": "Partner"},
                  "release": {"version": "1.1.0"},
                  "resources": [{"type": "theme", "key": "partner-theme", "name": "Partner Theme", "detailUrl": "./resources/themes/partner-theme.json"}]
                }
                """.trimIndent(),
            )
            Files.writeString(
                dir.resolve("resources/themes/partner-theme.json"),
                """{"schemaVersion": 8, "resource": {"type": "theme", "key": "partner-theme", "name": "Partner Theme", "documentStyles": {"fontSize": "12pt"}}}""",
            )
            return dir.resolve("catalog.json").toUri().toString()
        }

        private const val PUBLISHED_MODEL =
            """{"root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": ["root:children"]}, "body": {"id": "body", "type": "text", "slots": [], "props": {"content": {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "Dear customer"}]}]}}}}, "slots": {"root:children": {"id": "root:children", "name": "children", "nodeId": "root", "children": ["body"]}}}"""
        private const val DRAFT_MODEL =
            """{"root": "root", "nodes": {"root": {"id": "root", "type": "root", "slots": ["root:children"]}, "body": {"id": "body", "type": "text", "slots": [], "props": {"content": {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "Draft wording"}]}]}}}}, "slots": {"root:children": {"id": "root:children", "name": "children", "nodeId": "root", "children": ["body"]}}}"""

        /**
         * A letters catalog of [tenant] with an invoice template: version 1 published, version 2
         * unpublished work on top, a published contract; its theme is [themeCatalog]'s `house`, its own when
         * that is letters.
         */
        private fun letters(tenant: TenantKey, themeCatalog: String): String = """
            INSERT INTO catalogs (id, tenant_key, name, type) VALUES ('letters', '${tenant.value}', 'Letters', 'AUTHORED');
            INSERT INTO themes (id, tenant_key, catalog_key, name, document_styles)
            SELECT 'house', '${tenant.value}', 'letters', 'House', '{"fontSize": "10pt"}' WHERE '$themeCatalog' = 'letters';
            INSERT INTO catalog_releases (tenant_key, catalog_key, version, fingerprint, manifest_snapshot)
            VALUES ('${tenant.value}', 'letters', '1.1.0', '${"9".repeat(64)}', '{}'::jsonb);
            INSERT INTO document_templates (id, tenant_key, catalog_key, name, theme_resource_id)
            SELECT 'invoice', '${tenant.value}', 'letters', 'Invoice', resource_id FROM themes WHERE tenant_key = '${tenant.value}' AND catalog_key = '$themeCatalog' AND id = 'house';
            INSERT INTO template_variants (id, tenant_key, template_resource_id, title, is_default)
            SELECT 'default', '${tenant.value}', resource_id, 'Default', true FROM document_templates WHERE tenant_key = '${tenant.value}' AND id = 'invoice';
            INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status, published_at)
            SELECT 1, '${tenant.value}', resource_id, 'default', '$PUBLISHED_MODEL', 'published', NOW() FROM document_templates WHERE tenant_key = '${tenant.value}' AND id = 'invoice';
            INSERT INTO template_versions (id, tenant_key, template_resource_id, variant_key, template_model, status)
            SELECT 2, '${tenant.value}', resource_id, 'default', '$DRAFT_MODEL', 'draft' FROM document_templates WHERE tenant_key = '${tenant.value}' AND id = 'invoice';
            INSERT INTO contract_versions (id, tenant_key, template_resource_id, data_model, data_examples, status, published_at)
            SELECT 1, '${tenant.value}', resource_id, '{"type": "object", "properties": {"name": {"type": "string"}}}',
                   '[{"id": "8a6b4bde-2bb5-4d5e-9bd2-5a6f0b3a1c01", "name": "Default", "data": {"name": "World"}}]', 'published', NOW()
            FROM document_templates WHERE tenant_key = '${tenant.value}' AND id = 'invoice';
        """.trimIndent()

        /** The system catalog a 1.x tenant was subscribed to, at the release 1.x shipped. */
        private fun system(tenant: TenantKey): String = """
            INSERT INTO catalogs (id, tenant_key, name, type, source_url, installed_release_version, installed_fingerprint, installed_at)
            VALUES ('system', '${tenant.value}', 'System', 'SUBSCRIBED', 'classpath:epistola/catalogs/system/catalog.json', '1.3.0',
                    '74c0b2d145c4a970830b5ccbeb092b20abe7c8e9559f1d6b368147bac4bc09ba', NOW());
        """.trimIndent()

        private fun seed(partnerSource: String): String = listOf(
            // Two authored catalogs: letters' template uses shared's theme.
            "INSERT INTO tenants (id, name) VALUES ('${DEPS.value}', 'Deps');",
            system(DEPS),
            "INSERT INTO catalogs (id, tenant_key, name, type) VALUES ('shared', '${DEPS.value}', 'Shared', 'AUTHORED');",
            "INSERT INTO themes (id, tenant_key, catalog_key, name, document_styles) VALUES ('house', '${DEPS.value}', 'shared', 'House', '{\"fontSize\": \"10pt\"}');",
            letters(DEPS, "shared"),
            // A catalog subscribed to from a URL, with the content 1.x installed of its release 1.0.0.
            "INSERT INTO tenants (id, name) VALUES ('${SUBSCRIBER.value}', 'Subscriber');",
            system(SUBSCRIBER),
            """
            INSERT INTO catalogs (id, tenant_key, name, type, source_url, installed_release_version, installed_fingerprint, installed_at)
            VALUES ('partner', '${SUBSCRIBER.value}', 'Partner', 'SUBSCRIBED', '$partnerSource', '1.0.0', '${"8".repeat(64)}', NOW());
            INSERT INTO themes (id, tenant_key, catalog_key, name, document_styles) VALUES ('partner-theme', '${SUBSCRIBER.value}', 'partner', 'Partner Theme', '{"fontSize": "10pt"}');
            """.trimIndent(),
            // An environment that had the letters invoice's version 1 activated.
            "INSERT INTO tenants (id, name) VALUES ('${ENV.value}', 'Env');",
            system(ENV),
            letters(ENV, "letters"),
            """
            INSERT INTO environments (id, tenant_key, name) VALUES ('production', '${ENV.value}', 'Production');
            INSERT INTO environment_activations (tenant_key, environment_key, variant_key, version_key, template_resource_id)
            SELECT '${ENV.value}', 'production', 'default', 1, resource_id FROM document_templates WHERE tenant_key = '${ENV.value}' AND id = 'invoice';
            """.trimIndent(),
        ).joinToString("\n")
    }
}

/** [OneXUpgradeScenariosIT] on PostgreSQL 17. */
@Import(OneXUpgradeScenariosOnPostgres17IT.Database::class)
class OneXUpgradeScenariosOnPostgres17IT : OneXUpgradeScenariosIT() {
    @TestConfiguration(proxyBeanMethods = false)
    class Database {
        @Bean
        @Primary
        fun oneXConnectionDetails(): JdbcConnectionDetails = oneXDatabase(17)
    }
}

/** [OneXUpgradeScenariosIT] on PostgreSQL 18. */
@Import(OneXUpgradeScenariosOnPostgres18IT.Database::class)
class OneXUpgradeScenariosOnPostgres18IT : OneXUpgradeScenariosIT() {
    @TestConfiguration(proxyBeanMethods = false)
    class Database {
        @Bean
        @Primary
        fun oneXConnectionDetails(): JdbcConnectionDetails = oneXDatabase(18)
    }
}
