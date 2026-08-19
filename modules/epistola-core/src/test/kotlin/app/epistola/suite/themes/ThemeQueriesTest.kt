// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.ArchiveVersion
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateVersion
import app.epistola.suite.templates.model.ThemeRefOverride
import app.epistola.suite.templates.model.createDefaultTemplateModel
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.themes.queries.GetThemeUsagePage
import app.epistola.suite.themes.queries.ListThemes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class ThemeQueriesTest : IntegrationTestBase() {
    @Autowired
    private lateinit var themeStyleResolver: ThemeStyleResolver

    @Test
    fun `effective theme follows cascade without merging template overrides`(): Unit = withMediator {
        val tenant = createTenant("Theme Resolver Cascade")
        val catalogId = CatalogId.default(TenantId(tenant.id))
        val tenantTheme = ThemeId(ThemeKey.of("tenant-theme"), catalogId)
        val templateTheme = ThemeId(ThemeKey.of("template-theme"), catalogId)
        val variantTheme = ThemeId(ThemeKey.of("variant-theme"), catalogId)
        CreateTheme(id = tenantTheme, name = "Tenant", spacingUnit = 4f).execute()
        CreateTheme(id = templateTheme, name = "Template", spacingUnit = 5f).execute()
        CreateTheme(
            id = variantTheme,
            name = "Variant",
            documentStyles = mapOf("color" to "#112233", "fontSize" to "10pt"),
            spacingUnit = 6f,
        ).execute()

        val inheritedModel = createDefaultTemplateModel(VariantKey.INITIAL)
        assertThat(
            themeStyleResolver.resolveTheme(
                tenantId = tenant.id,
                templateDefaultThemeId = null,
                tenantDefaultThemeId = tenantTheme.key,
                templateModel = inheritedModel,
                tenantDefaultThemeCatalogKey = tenantTheme.catalogKey,
            )?.name,
        ).isEqualTo("Tenant")
        assertThat(
            themeStyleResolver.resolveTheme(
                tenantId = tenant.id,
                templateDefaultThemeId = templateTheme.key,
                tenantDefaultThemeId = tenantTheme.key,
                templateModel = inheritedModel,
                templateCatalogKey = templateTheme.catalogKey,
                tenantDefaultThemeCatalogKey = tenantTheme.catalogKey,
            )?.name,
        ).isEqualTo("Template")

        val overriddenModel = inheritedModel.copy(
            themeRef = ThemeRefOverride(variantTheme.key.value, variantTheme.catalogKey.value),
            documentStylesOverride = mapOf("color" to "#abcdef"),
        )
        val rawTheme = themeStyleResolver.resolveTheme(
            tenantId = tenant.id,
            templateDefaultThemeId = templateTheme.key,
            tenantDefaultThemeId = tenantTheme.key,
            templateModel = overriddenModel,
            templateCatalogKey = templateTheme.catalogKey,
            tenantDefaultThemeCatalogKey = tenantTheme.catalogKey,
        )
        val mergedStyles = themeStyleResolver.resolveStyles(
            tenantId = tenant.id,
            templateDefaultThemeId = templateTheme.key,
            tenantDefaultThemeId = tenantTheme.key,
            templateModel = overriddenModel,
            templateCatalogKey = templateTheme.catalogKey,
            tenantDefaultThemeCatalogKey = tenantTheme.catalogKey,
        )

        assertThat(rawTheme?.name).isEqualTo("Variant")
        assertThat(rawTheme?.spacingUnit).isEqualTo(6f)
        assertThat(rawTheme?.documentStyles?.get("color")).isEqualTo("#112233")
        assertThat(mergedStyles.documentStyles["color"]).isEqualTo("#abcdef")
        assertThat(mergedStyles.documentStyles["fontSize"]).isEqualTo("10pt")
    }

    @Test
    fun `GetTheme returns the theme`(): Unit = withMediator {
        val tenant = createTenant("Theme Get")
        val catalogId = CatalogId.default(TenantId(tenant.id))
        val themeId = ThemeId(ThemeKey.of("brand"), catalogId)
        val created = CreateTheme(id = themeId, name = "Brand Theme", description = "Corporate look").execute()

        val found = GetTheme(id = themeId).query()

        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(created.id)
        assertThat(found.tenantKey).isEqualTo(tenant.id)
        assertThat(found.name).isEqualTo("Brand Theme")
        assertThat(found.description).isEqualTo("Corporate look")
    }

    @Test
    fun `GetTheme returns null for an unknown theme`(): Unit = withMediator {
        val tenant = createTenant("Theme Get Missing")
        val themeId = ThemeId(ThemeKey.of("does-not-exist"), CatalogId.default(TenantId(tenant.id)))

        assertThat(GetTheme(id = themeId).query()).isNull()
    }

    @Test
    fun `GetTheme does not leak themes across tenants`(): Unit = withMediator {
        val owner = createTenant("Theme Get Owner")
        val other = createTenant("Theme Get Other")
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId.default(TenantId(owner.id))), name = "Owner Theme").execute()

        val crossTenant = GetTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId.default(TenantId(other.id)))).query()

        assertThat(crossTenant).isNull()
    }

    @Test
    fun `GetTheme distinguishes the same slug across catalogs`(): Unit = withMediator {
        val tenant = createTenant("Theme Get Catalog Scope")
        val tenantId = TenantId(tenant.id)
        val firstCatalog = CatalogKey.of("first")
        val secondCatalog = CatalogKey.of("second")
        CreateCatalog(tenantKey = tenant.id, id = firstCatalog, name = "First").execute()
        CreateCatalog(tenantKey = tenant.id, id = secondCatalog, name = "Second").execute()
        val firstTheme = ThemeId(ThemeKey.of("shared"), CatalogId(firstCatalog, tenantId))
        val secondTheme = ThemeId(ThemeKey.of("shared"), CatalogId(secondCatalog, tenantId))
        CreateTheme(id = firstTheme, name = "First Theme").execute()
        CreateTheme(id = secondTheme, name = "Second Theme").execute()

        assertThat(GetTheme(id = firstTheme).query()?.name).isEqualTo("First Theme")
        assertThat(GetTheme(id = secondTheme).query()?.name).isEqualTo("Second Theme")
    }

    @Test
    fun `UpdateTheme changes only the requested catalog when slugs match`(): Unit = withMediator {
        val tenant = createTenant("Theme Update Catalog Scope")
        val tenantId = TenantId(tenant.id)
        val firstCatalog = CatalogKey.of("first")
        val secondCatalog = CatalogKey.of("second")
        CreateCatalog(tenantKey = tenant.id, id = firstCatalog, name = "First").execute()
        CreateCatalog(tenantKey = tenant.id, id = secondCatalog, name = "Second").execute()
        val firstTheme = ThemeId(ThemeKey.of("shared"), CatalogId(firstCatalog, tenantId))
        val secondTheme = ThemeId(ThemeKey.of("shared"), CatalogId(secondCatalog, tenantId))
        CreateTheme(id = firstTheme, name = "First Theme").execute()
        CreateTheme(id = secondTheme, name = "Second Theme").execute()

        val updated = UpdateTheme(id = firstTheme, name = "Updated First Theme").execute()

        assertThat(updated?.name).isEqualTo("Updated First Theme")
        assertThat(GetTheme(id = firstTheme).query()?.name).isEqualTo("Updated First Theme")
        assertThat(GetTheme(id = secondTheme).query()?.name).isEqualTo("Second Theme")
    }

    @Test
    fun `ListThemes returns the tenant's themes`(): Unit = withMediator {
        val tenant = createTenant("Theme List")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalogId), name = "Brand Theme").execute()
        CreateTheme(id = ThemeId(ThemeKey.of("minimal"), catalogId), name = "Minimal Theme").execute()

        val themes = ListThemes(tenantId = tenantId).query()

        // The list also contains the bundled system-catalog theme(s) every tenant gets.
        assertThat(themes.map { it.name }).contains("Brand Theme", "Minimal Theme")
    }

    @Test
    fun `ListThemes filters by search term`(): Unit = withMediator {
        val tenant = createTenant("Theme List Search")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalogId), name = "Brand Theme").execute()
        CreateTheme(id = ThemeId(ThemeKey.of("minimal"), catalogId), name = "Minimal Theme").execute()

        val matches = ListThemes(tenantId = tenantId, searchTerm = "brand").query()

        assertThat(matches).extracting<String> { it.name }.containsExactly("Brand Theme")
    }

    @Test
    fun `ListThemes filters by catalog`(): Unit = withMediator {
        val tenant = createTenant("Theme List Catalog")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalogId), name = "Brand Theme").execute()

        val defaultCatalogThemes = ListThemes(tenantId = tenantId, catalogKey = CatalogKey.DEFAULT).query()

        // Only the authored theme lives in the default catalog; the system catalog's themes are excluded.
        assertThat(defaultCatalogThemes).extracting<String> { it.name }.containsExactly("Brand Theme")
        assertThat(defaultCatalogThemes).allMatch { it.catalogKey == CatalogKey.DEFAULT }
    }

    @Test
    fun `theme usage resolves every cascade source and excludes archived versions`(): Unit = withMediator {
        val tenant = createTenant("Theme Usage Cascade")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        val targetTheme = ThemeId(ThemeKey.of("brand"), catalogId)
        val otherTheme = ThemeId(ThemeKey.of("other"), catalogId)
        CreateTheme(id = targetTheme, name = "Brand").execute()
        CreateTheme(id = otherTheme, name = "Other").execute()
        SetTenantDefaultTheme(tenant.id, targetTheme.key, targetTheme.catalogKey).execute()

        val tenantTemplateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
        CreateDocumentTemplate(tenantTemplateId, "Tenant Default").execute().withRequiredDataExample()

        val templateDefaultId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
        CreateDocumentTemplate(templateDefaultId, "Template Default").execute().withRequiredDataExample()
        UpdateDocumentTemplate(
            id = templateDefaultId,
            themeId = targetTheme.key,
            themeCatalogKey = targetTheme.catalogKey,
        ).execute()

        val overrideTemplateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
        CreateDocumentTemplate(overrideTemplateId, "Variant Override").execute().withRequiredDataExample()
        UpdateDocumentTemplate(
            id = overrideTemplateId,
            themeId = otherTheme.key,
            themeCatalogKey = otherTheme.catalogKey,
        ).execute()
        val overrideVersionId = VersionId(
            VersionKey.of(1),
            VariantId(VariantKey.INITIAL, overrideTemplateId),
        )
        UpdateVersion(
            overrideVersionId,
            createDefaultTemplateModel(VariantKey.INITIAL).copy(
                themeRef = ThemeRefOverride(
                    themeId = targetTheme.key.value,
                    catalogKey = targetTheme.catalogKey.value,
                ),
            ),
        ).execute()
        PublishVersion(overrideVersionId).execute()

        val archivedVariant = VariantId(VariantKey.of("archive"), templateDefaultId)
        CreateVariant(
            id = archivedVariant,
            title = "Archived",
            description = null,
        ).execute()
        val archivedVersionId = VersionId(VersionKey.of(1), archivedVariant)
        PublishVersion(archivedVersionId).execute()
        ArchiveVersion(archivedVersionId).execute()

        val usages = GetThemeUsagePage(targetTheme, limit = 20).query()

        assertThat(usages.items)
            .extracting<String> { "${it.templateName}:${it.source}:${it.versionStatus}:${it.frozenSnapshot}" }
            .containsExactlyInAnyOrder(
                "Tenant Default:TENANT_DEFAULT:DRAFT:false",
                "Template Default:TEMPLATE_DEFAULT:DRAFT:false",
                "Variant Override:VARIANT_OVERRIDE:PUBLISHED:true",
            )
        assertThat(usages.total).isEqualTo(3)
        assertThat(usages.items).noneMatch { it.variantKey == archivedVariant.key }
    }

    @Test
    fun `theme usage is catalog scoped and paginated`(): Unit = withMediator {
        val tenant = createTenant("Theme Usage Catalog")
        val tenantId = TenantId(tenant.id)
        val marketingKey = CatalogKey.of("marketing")
        CreateCatalog(tenantKey = tenant.id, id = marketingKey, name = "Marketing").execute()
        val defaultTheme = ThemeId(ThemeKey.of("brand"), CatalogId.default(tenantId))
        val marketingTheme = ThemeId(ThemeKey.of("brand"), CatalogId(marketingKey, tenantId))
        CreateTheme(id = defaultTheme, name = "Default Brand").execute()
        CreateTheme(id = marketingTheme, name = "Marketing Brand").execute()

        repeat(2) { index ->
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
            CreateDocumentTemplate(templateId, "Template ${index + 1}").execute()
            UpdateDocumentTemplate(
                id = templateId,
                themeId = marketingTheme.key,
                themeCatalogKey = marketingTheme.catalogKey,
            ).execute()
        }

        val firstPage = GetThemeUsagePage(marketingTheme, limit = 1).query()
        val defaultCatalogUsages = GetThemeUsagePage(defaultTheme).query()

        assertThat(firstPage.items).hasSize(1)
        assertThat(firstPage.total).isEqualTo(2)
        assertThat(defaultCatalogUsages.items).isEmpty()
    }
}
