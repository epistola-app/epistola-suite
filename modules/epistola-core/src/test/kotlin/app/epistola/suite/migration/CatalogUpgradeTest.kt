// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReceiveOneXSubscribedReleases
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.queries.GetGenerationReadiness
import app.epistola.suite.environments.queries.ONE_X_CATALOGS
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.toPath

/**
 * What the application does with a catalog the 2.0 upgrade brought over from 1.x, which cut no
 * release (ADR 0035; the drafts themselves are `V20261101000900`'s, which `OneXUpgradeScenariosIT`
 * runs on a 1.x database):
 *
 * - releasing pins every dependency the upgrade declared by name to that catalog's highest release,
 *   and refuses, naming it, a dependency whose catalog has none;
 * - the tenant home lists the 1.x catalogs still without a release;
 * - a catalog 1.x subscribed to from a URL receives its source's release.
 *
 * What the upgrade wrote is raw SQL on purpose: no command declares by name, or makes a 1.x catalog.
 */
class CatalogUpgradeTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")

    private fun tenant(name: String): TenantKey = createTenant(name).id

    private fun catalog(tenant: TenantKey, key: CatalogKey): CatalogId = withMediator {
        CreateCatalog(tenant, key, key.value.replaceFirstChar { it.uppercase() }).execute()
        testCatalog(TenantId(tenant), key)
    }

    /** As the upgrade declares what a 1.x catalog used: by name, pinning nothing, on its next version. */
    private fun declareByName(catalog: CatalogId, used: CatalogId) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            """
            INSERT INTO catalog_version_dependencies (tenant_key, catalog_id, version_id, registry, namespace, dependency_key, alias, position)
            SELECT c.tenant_key, c.catalog_id, c.next_version_id, u.registry, u.namespace, u.catalog_key, u.catalog_key, 0
            FROM catalogs c JOIN catalogs u ON u.tenant_key = c.tenant_key AND u.catalog_id = ${catalogAt("t", "u")}
            WHERE c.tenant_key = :t AND c.catalog_id = ${catalogAt("t", "c")}
            """,
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .bindCatalog("u", used)
            .execute()
    }

    /** A theme of [catalog] in the system catalog's Inter: it uses the system catalog. */
    private fun useSystemFont(catalog: CatalogId) = withMediator {
        val theme = ThemeId(ThemeKey.of("brand"), catalog)
        CreateTheme(theme, "Brand").execute()
        UpdateTheme(theme, documentStyles = mapOf("fontFamily" to mapOf("key" to "inter", "catalogKey" to SYSTEM_CATALOG_KEY.value))).execute()
        MarkCatalogReady(catalog).execute()
    }

    @Test
    fun `releasing pins a dependency declared by name to its catalog's highest release`() {
        val tenant = tenant("upgrade-pins")
        val catalog = catalog(tenant, letters)
        useSystemFont(catalog)
        val system = testCatalog(TenantId(tenant), SYSTEM_CATALOG_KEY)
        // Using the system font declared it, pinned; the upgrade declares it pinning nothing.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                "UPDATE catalog_version_dependencies SET release_version = NULL, release_fingerprint = NULL, target_version_id = NULL " +
                    "WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND dependency_key = :k",
            )
                .bind("t", tenant)
                .bindCatalog("c", catalog)
                .bind("k", SYSTEM_CATALOG_KEY.value)
                .execute()
        }
        assertThat(withMediator { ListCatalogDependencies(catalog).query() }.single().releaseVersion).isNull()
        val installed = requireNotNull(withMediator { GetCatalog(system).query() }?.latestReleaseVersion)

        val result = withMediator { ReleaseCatalogDraft(catalog).execute() }

        assertThat(result.pinnedDependencies.map { it.catalogKey to it.releaseVersion }).containsExactly(SYSTEM_CATALOG_KEY.value to installed)
        assertThat(withMediator { ListCatalogDependencies(catalog).query() }.single().releaseVersion).isEqualTo(installed)
    }

    @Test
    fun `releasing is refused, naming it, while a dependency declared by name has no release`() {
        val tenant = tenant("upgrade-unpinned")
        val catalog = catalog(tenant, letters)
        useSystemFont(catalog)
        declareByName(catalog, catalog(tenant, shared))

        assertThatThrownBy { withMediator { ReleaseCatalogDraft(catalog).execute() } }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("shared has no release to pin; release shared first")
    }

    @Test
    fun `the tenant home lists the 1x catalogs that have no release yet`() {
        val tenant = tenant("upgrade-readiness")
        val catalog = catalog(tenant, letters)
        useSystemFont(catalog)
        catalog(tenant, shared)
        // As the upgrade lists the authored 1.x catalogs: letters only, shared is new in 2.0.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO app_metadata (key, value)
                SELECT :key, jsonb_build_array(jsonb_build_object('tenant', c.tenant_key, 'catalog', c.catalog_id::text))
                FROM catalogs c WHERE c.tenant_key = :t AND c.catalog_id = ${catalogAt("t", "c")}
                ON CONFLICT (key) DO UPDATE SET value = app_metadata.value || EXCLUDED.value
                """,
            )
                .bind("key", ONE_X_CATALOGS)
                .bind("t", tenant)
                .bindCatalog("c", catalog)
                .execute()
        }
        assertThat(withMediator { GetGenerationReadiness(tenant).query() }.publishedIn1x).containsExactly("Letters")

        withMediator { ReleaseCatalogDraft(catalog).execute() }

        assertThat(withMediator { GetGenerationReadiness(tenant).query() }.publishedIn1x).isEmpty()
    }

    @Test
    fun `a catalog 1x subscribed to from a URL receives its source's release, once`(@TempDir tmp: Path) {
        val tenant = tenant("upgrade-subscribed")
        val subscribed = withMediator { RegisterCatalog(tenantKey = tenant, sourceUrl = fixture(tmp), authType = AuthType.NONE).execute() }.let { testCatalog(TenantId(tenant), it.id) }
        assertThat(withMediator { ReceiveOneXSubscribedReleases(tenant).execute() }.received)
            .`as`("a catalog registered under 2.0 is installed when someone installs it").isEmpty()
        // 1.x recorded that it subscribed: the column the upgrade still reads, which no command writes.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("UPDATE catalogs SET type = 'SUBSCRIBED' WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
                .bind("t", tenant)
                .bindCatalog("c", subscribed)
                .execute()
        }

        val first = withMediator { ReceiveOneXSubscribedReleases(tenant).execute() }

        assertThat(first.failed).isEmpty()
        assertThat(first.received).containsExactly(subscribed)
        assertThat(withMediator { GetCatalog(subscribed).query() }?.latestReleaseVersion).isEqualTo("1.0.0")
        assertThat(withMediator { ReceiveOneXSubscribedReleases(tenant).execute() }.received).`as`("once received, nothing to do").isEmpty()
    }

    /** A writable copy of the dependency-test fixture at [dir]: a catalog's source. */
    private fun fixture(dir: Path): String {
        val src = javaClass.classLoader.getResource("epistola/catalogs/test/dependency-test/catalog.json")!!.toURI().toPath().parent
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val target = dir.resolve(src.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return dir.resolve("catalog.json").toUri().toString()
    }
}
