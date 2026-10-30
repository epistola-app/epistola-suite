// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.assets.AssetIntegrationTest
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.invariants.CatalogInvariants
import app.epistola.suite.testing.invariants.SkipCatalogInvariants
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The catalog invariants every integration test is checked against (`CatalogInvariantsExtension`)
 * hold for catalogs built through commands, and each one finds the inconsistency it names. A rule
 * no corruption here can trip would pass vacuously.
 *
 * The world: `base@1.0.0`; `shared@1.0.0` pinning it, with theme `brand`; `letters@1.0.0` pinning
 * `shared@1.0.0`, its template `invoice` using `shared:brand`, and image `logo`. Each test breaks it
 * with one statement, since no command can.
 */
@SkipCatalogInvariants("corrupts its catalogs on purpose, to see each invariant find it")
class CatalogInvariantsTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `catalogs built through commands break no invariant`() {
        val tenant = world()
        assertThat(violations(tenant)).isEmpty()
    }

    @Test
    fun `a draft based on another catalog's draft`() = assertFinds(
        "base-is-release",
        """
        UPDATE catalog_versions SET base_version_id = (SELECT next_version_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'shared')
        WHERE tenant_key = :t AND version_id = (SELECT next_version_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'letters')
        """,
    )

    @Test
    fun `a release that pins no release`() = assertFinds(
        "release-pins-releases",
        """
        UPDATE catalog_version_dependencies SET release_version = NULL, release_fingerprint = NULL, target_version_id = NULL
        WHERE tenant_key = :t AND version_id IN ($LETTERS_RELEASES)
        """,
    )

    @Test
    fun `a declaration of a catalog the tenant does not have`() = assertFinds(
        "dependency-present",
        "UPDATE catalog_version_dependencies SET dependency_key = 'gone' WHERE tenant_key = :t AND version_id = ($LETTERS_DRAFT)",
    )

    @Test
    fun `a release pinning a release the tenant does not have`() = assertFinds(
        "pinned-release-present",
        "UPDATE catalog_version_dependencies SET release_version = '9.9.9' WHERE tenant_key = :t AND version_id IN ($LETTERS_RELEASES)",
    )

    @Test
    fun `a declaration targeting a version of another catalog`() = assertFinds(
        "dependency-target-in-named-catalog",
        """
        UPDATE catalog_version_dependencies SET target_version_id = (SELECT next_version_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'base')
        WHERE tenant_key = :t AND version_id = ($LETTERS_DRAFT)
        """,
    )

    @Test
    fun `two catalogs using each other`() = assertFinds(
        "catalogs-never-use-each-other",
        """
        UPDATE catalog_version_dependencies SET dependency_key = 'letters', alias = 'letters'
        WHERE tenant_key = :t AND version_id = (SELECT next_version_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'shared')
        """,
    )

    @Test
    fun `a draft resource whose identity is registered at another address`() = assertFinds(
        "draft-identity-registered",
        "UPDATE catalog_resources SET resource_key = 'elsewhere' WHERE tenant_key = :t AND resource_type = 'image'",
    )

    @Test
    fun `a released entry without a fingerprint`() = assertFinds(
        "released-entry-fingerprinted",
        "UPDATE release_resources SET fingerprint = NULL WHERE tenant_key = :t AND resource_type = 'template' AND version_id IN ($LETTERS_RELEASES)",
    )

    @Test
    fun `an image naming bytes that are not stored`() = assertFinds(
        "image-bytes-present",
        """
        UPDATE resource_contents
        SET payload = CASE WHEN payload -> 'resource' IS NOT NULL THEN jsonb_set(payload, '{resource,contentHash}', '"${"0".repeat(64)}"')
                           ELSE jsonb_set(payload, '{contentHash}', '"${"0".repeat(64)}"') END
        WHERE tenant_key = :t AND kind = 'image'
        """,
    )

    @Test
    fun `a release using a resource the release it pins does not hold`() = assertFinds(
        "release-reference-resolves",
        "UPDATE resource_references SET resource_key = 'gone' WHERE tenant_key = :t AND resource_type = 'theme' AND resource_key = 'brand'",
    )

    private fun assertFinds(invariant: String, corruption: String) {
        val tenant = world()
        jdbi.useHandle<Exception> { it.createUpdate(corruption).bind("t", tenant.value).execute() }
        assertThat(violations(tenant).map { it.invariant }).contains(invariant)
    }

    private fun violations(tenant: TenantKey) = CatalogInvariants.check(jdbi, tenant.value)

    private fun world(): TenantKey {
        val tenant = createTenant("invariants").id
        val base = catalogIn(tenant, "base")
        val shared = catalogIn(tenant, "shared")
        val letters = catalogIn(tenant, "letters")
        val invoice = TemplateId(TemplateKey.of("invoice"), letters)
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("plain"), base), "Plain").execute()
            mediator.releaseNext(base)
            declareDependency(tenant, shared.key, on = base.key)
            CreateTheme(ThemeId(ThemeKey.of("brand"), shared), "Brand").execute()
            mediator.releaseNext(shared)
            declareDependency(tenant, letters.key, on = shared.key)
            UploadAsset(letters, "logo", AssetMediaType.PNG, AssetIntegrationTest.createMinimalPng(), 1, 1).execute()
            CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(invoice, themeId = ThemeKey.of("brand"), themeCatalog = shared).execute()
            UpdateVariantContent(VariantId(VariantKey.INITIAL, invoice), TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
            mediator.publishAndRelease(VariantId(VariantKey.INITIAL, invoice))
        }
        return tenant
    }

    private fun catalogIn(tenant: TenantKey, slug: String): CatalogId {
        val key = CatalogKey.of(slug)
        withMediator { CreateCatalog(tenantKey = tenant, id = key, name = slug).execute() }
        return testCatalog(TenantId(tenant), key)
    }

    private companion object {
        const val LETTERS_RELEASES =
            "SELECT version_id FROM catalog_versions WHERE tenant_key = :t AND state = 'released' " +
                "AND catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'letters')"
        const val LETTERS_DRAFT = "SELECT next_version_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'letters'"
    }
}
