// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource

/**
 * Seeds catalogs, releases, deployments and mirror choices through commands, as people make them.
 * Tenants are named: the mirror is unavailable while they are confidential (ADR 0052 §1).
 */
@TestPropertySource(properties = ["epistola.support.privacy.tenant-identity=named"])
abstract class CatalogMirrorTestBase : IntegrationTestBase() {
    @Autowired
    protected lateinit var privacy: SupportPrivacy

    /** Creates [key] with a theme and releases it as each of [versions], every release with different content. */
    protected fun releasedCatalog(tenant: TenantKey, key: String, vararg versions: String): CatalogId = withMediator {
        val catalogKey = CatalogKey.of(key)
        CreateCatalog(tenantKey = tenant, id = catalogKey, name = key.replaceFirstChar(Char::uppercase)).execute()
        val catalog = testCatalog(tenant, catalogKey)
        val theme = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), catalogKey))
        // Named after the tenant and catalog, so no two catalogs release the same content (content hash).
        CreateTheme(theme, "Brand ${tenant.value} $key", documentStyles = mapOf("color" to "#000000"), catalogVersion = null).execute()
        versions.forEachIndexed { index, version ->
            if (index > 0) UpdateTheme(theme, documentStyles = mapOf("color" to "#00000$index"), catalogVersion = null).execute()
            MarkCatalogReady(catalog, catalogVersion = null).execute()
            ReleaseCatalogDraft(catalog, version, catalogVersion = null).execute()
        }
        catalog
    }

    /** Makes [catalog]'s next release depend on [dependency] at [version], and releases it as [as]. */
    protected fun releaseDependingOn(catalog: CatalogId, dependency: CatalogId, version: String, `as`: String) = withMediator {
        val target = GetCatalog(dependency).query()!!
        UpdateCatalogDependencies(
            catalog,
            listOf(DeclaredCatalogDependency(target.registry, target.namespace, dependency.key.value, version)),
            catalogVersion = null,
        ).execute()
        MarkCatalogReady(catalog, catalogVersion = null).execute()
        ReleaseCatalogDraft(catalog, `as`, catalogVersion = null).execute()
    }

    protected fun environment(tenant: TenantKey, key: String, production: Boolean): EnvironmentId = withMediator {
        val id = EnvironmentId(EnvironmentKey.of(key), TenantId(tenant))
        CreateEnvironment(id, key.uppercase(), production = production).execute()
        id
    }

    protected fun deploy(environment: EnvironmentId, catalog: CatalogId, version: String) = withMediator {
        DeployCatalogRelease(environment, catalog, version).execute()
    }

    /** The content hash of each release of [catalog], by number, as the catalog records it. */
    protected fun contentHashes(catalog: CatalogId): Map<String, String> = withMediator {
        ListCatalogReleases(catalog, CatalogReleaseStatus.RELEASED).query().associate { it.version!! to it.contentHash!! }
    }

    protected fun optIn(tenant: TenantKey, shareByDefault: Boolean) = withMediator {
        OptInCatalogMirror(tenant, shareByDefault, CatalogMirror.CONSENT_VERSION).execute()
    }

    protected fun policy(catalog: CatalogId, policy: CatalogMirrorPolicy) = withMediator { SetCatalogMirrorPolicy(catalog, policy).execute() }

    /**
     * Opts in sharing every catalog but the system catalog, which is marked never to be shared: for
     * tests about the tenant's own catalogs, which depend on nothing, so their sets stay small.
     */
    protected fun optInOwnCatalogs(tenant: TenantKey) {
        optIn(tenant, shareByDefault = true)
        policy(testCatalog(tenant, SYSTEM_CATALOG_KEY), CatalogMirrorPolicy.NEVER)
    }

    /** Every binding the tenant shares, walking the pages. */
    protected fun bindings(tenant: TenantKey, pageSize: Int = ListMirrorBindings.DEFAULT_LIMIT): List<MirrorBinding> = withMediator {
        val all = mutableListOf<MirrorBinding>()
        var cursor: MirrorBindingCursor? = null
        do {
            val page = ListMirrorBindings(tenant, cursor, pageSize).query()
            all += page.bindings
            cursor = page.next
        } while (cursor != null)
        all
    }

    protected fun settings(tenant: TenantKey): CatalogMirrorSettings = withMediator { GetCatalogMirrorSettings(tenant, limit = GetCatalogMirrorSettings.MAX_LIMIT).query() }
}
