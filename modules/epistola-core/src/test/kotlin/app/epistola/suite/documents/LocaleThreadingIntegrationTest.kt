// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.queries.PreviewVariant
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.testing.DocumentSetup
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * End-to-end coverage for the locale-threading seam that the unit tests can't
 * reach: a variant's persisted `system.locale` attribute, loaded from the real
 * database via `GetVariant`, resolving through `TenantLocaleResolver.resolveCulture`
 * to the [app.epistola.generation.RenderCulture] the render path consumes.
 *
 * The *content* assertion (nl-NL formats `1234.56` as `1.234,56` in the output)
 * lives in `:modules:generation`'s `DirectPdfRendererTest`, where iText belongs.
 * This test proves the wiring around it — that the persisted attribute reaches
 * the resolver and that `PreviewVariant` renders successfully with a non-default
 * culture — without pulling the PDF library into the business-logic module.
 */
@Timeout(30)
class LocaleThreadingIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var localeResolver: TenantLocaleResolver

    private val objectMapper = ObjectMapper()

    private fun emptyData(): ObjectNode = objectMapper.createObjectNode()

    @Test
    fun `variant system_locale resolves to a nl-NL culture and previews successfully`() = scenario {
        given {
            val tenant = tenant("Locale Tenant")
            val tenantId = TenantId(tenant.id)
            val template = template(tenant.id, "Locale Template")
            val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
            val variant = variant(
                compositeTemplateId,
                "Dutch",
                attributes = mapOf("system.locale" to "nl-NL"),
            )
            val compositeVariantId = VariantId(variant.id, compositeTemplateId)
            val templateModel = TestTemplateBuilder.buildMinimal(name = "Locale Template")
            val version = version(compositeVariantId, templateModel)
            DocumentSetup(tenant, template, variant, version)
        }.whenever { setup ->
            query(
                PreviewVariant(
                    tenantId = setup.tenant.id,
                    catalogKey = CatalogKey.DEFAULT,
                    templateId = setup.template.id,
                    variantId = setup.variant.id,
                    data = emptyData(),
                ),
            )
        }.then { setup, pdfBytes ->
            // The full pipeline runs end-to-end with a non-default-locale variant.
            assertThat(pdfBytes).isNotEmpty()

            // The persisted variant attribute resolves to the nl-NL culture via the
            // GetVariant DB load — the seam the three render call sites used to each
            // repeat by hand, now owned by resolveCulture(tenant, variantId).
            val tenantId = TenantId(setup.tenant.id)
            val compositeTemplateId = TemplateId(setup.template.id, CatalogId.default(tenantId))
            val compositeVariantId = VariantId(setup.variant.id, compositeTemplateId)
            val culture = localeResolver.resolveCulture(setup.tenant, compositeVariantId)
            assertThat(culture.locale.toLanguageTag()).isEqualTo("nl-NL")
        }
    }

    @Test
    fun `variant without a locale attribute falls back to the app default culture`() = scenario {
        given {
            val tenant = tenant("Default Locale Tenant")
            val tenantId = TenantId(tenant.id)
            val template = template(tenant.id, "Default Template")
            val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
            val variant = variant(compositeTemplateId, "Plain")
            val compositeVariantId = VariantId(variant.id, compositeTemplateId)
            val templateModel = TestTemplateBuilder.buildMinimal(name = "Default Template")
            val version = version(compositeVariantId, templateModel)
            DocumentSetup(tenant, template, variant, version)
        }.whenever { setup ->
            query(
                PreviewVariant(
                    tenantId = setup.tenant.id,
                    catalogKey = CatalogKey.DEFAULT,
                    templateId = setup.template.id,
                    variantId = setup.variant.id,
                    data = emptyData(),
                ),
            )
        }.then { setup, pdfBytes ->
            assertThat(pdfBytes).isNotEmpty()

            val tenantId = TenantId(setup.tenant.id)
            val compositeTemplateId = TemplateId(setup.template.id, CatalogId.default(tenantId))
            val compositeVariantId = VariantId(setup.variant.id, compositeTemplateId)
            val culture = localeResolver.resolveCulture(setup.tenant, compositeVariantId)
            // No tenant override + no variant attribute → app default (en-US).
            assertThat(culture.locale.toLanguageTag()).isEqualTo(localeResolver.applicationDefault)
        }
    }
}
