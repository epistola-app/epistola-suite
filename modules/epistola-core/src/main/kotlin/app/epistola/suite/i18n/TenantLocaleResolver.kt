// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.i18n

import app.epistola.generation.RenderCulture
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.stereotype.Component
import java.util.Locale

/**
 * Resolves the effective default locale for a render. Three-step chain:
 *
 * 1. **Variant attribute** — `system.locale` (preferred, catalog-qualified)
 *    or bare `locale` on the variant being rendered. Used as the per-template
 *    locale because variants are how language/brand splits are modelled.
 * 2. **Tenant override** — `tenants.default_locale`, set on the tenant's
 *    Defaults page (`/tenants/{id}/defaults`).
 * 3. **Application default** — `epistola.i18n.default-locale` configured in
 *    `application.yaml`.
 *
 * The string overloads return BCP-47 tags (e.g. `"nl-NL"`). The `Locale`
 * overloads return Java [Locale] instances suitable for [java.text.NumberFormat]
 * and [java.time.format.DateTimeFormatter]. `Locale.forLanguageTag` accepts
 * any BCP-47 input and yields `Locale.ROOT` for unparseable values, so the
 * boundary stays string-typed and the conversion is local to call sites.
 *
 * **Do not rename to `LocaleResolver`.** The lowercase Spring bean name
 * `localeResolver` collides with `org.springframework.web.servlet.LocaleResolver`,
 * which `RequestMappingHandlerAdapter` looks up by that exact name — context
 * refresh fails with a `BeanNotOfRequiredTypeException` if our class wins.
 */
@Component
class TenantLocaleResolver(
    private val properties: LocaleProperties,
) {
    fun resolve(tenant: Tenant): String = tenant.defaultLocale ?: properties.defaultLocale

    fun resolve(tenantId: TenantKey): String = GetTenant(tenantId).query()?.defaultLocale ?: properties.defaultLocale

    /**
     * Variant-aware chain: variant attribute → tenant override → app default.
     *
     * The catalog-qualified `system.locale` key takes precedence over the bare
     * `locale` key (catalog-qualified is the recommended attribute form per
     * `AttributeValidation`). Blank string values are ignored as if the key
     * wasn't there.
     */
    fun resolve(tenant: Tenant, variantAttributes: Map<String, String>): String = resolveWithSource(tenant, variantAttributes).tag

    /**
     * [resolve]`(tenant, variantAttributes)`, saying which step of the chain answered.
     *
     * Formatting a date or a number in the installation default does no harm, but a check that reads
     * the text *as* that language does: a spellchecker handed a Dutch template as `en-US` flags every
     * word. [LocaleSource.INSTALLATION] tells such a caller that nobody chose the language.
     */
    fun resolveWithSource(tenant: Tenant, variantAttributes: Map<String, String>): ResolvedLocale {
        val variant = variantAttributes["system.locale"].nullIfBlank() ?: variantAttributes["locale"].nullIfBlank()
        return when {
            variant != null -> ResolvedLocale(variant, LocaleSource.VARIANT)
            tenant.defaultLocale != null -> ResolvedLocale(tenant.defaultLocale, LocaleSource.TENANT)
            else -> ResolvedLocale(properties.defaultLocale, LocaleSource.INSTALLATION)
        }
    }

    /** [Locale] flavour of [resolve]`(tenant, variantAttributes)`. */
    fun resolveLocale(tenant: Tenant, variantAttributes: Map<String, String>): Locale = Locale.forLanguageTag(resolve(tenant, variantAttributes))

    /** [Locale] flavour of [resolve]`(tenant)`. */
    fun resolveLocale(tenant: Tenant): Locale = Locale.forLanguageTag(resolve(tenant))

    /**
     * [RenderCulture] for a render: bundles the resolved locale with the
     * timezone so the renderer threads one value instead of a growing list of
     * culture parameters. Timezone is the engine default today — per-tenant
     * timezone resolution slots in here when it lands, without touching any
     * `renderPdf*` signature.
     */
    fun resolveCulture(tenant: Tenant, variantAttributes: Map<String, String>): RenderCulture = RenderCulture(locale = resolveLocale(tenant, variantAttributes))

    /**
     * [resolveCulture] that loads the variant's attributes itself, so the three
     * render call sites (preview, variant-preview, batch executor) don't each
     * repeat the `GetVariant` + null-handling dance. A missing variant resolves
     * to the tenant/app chain. The variant is read in [catalogVersion] (the version being rendered)
     * when that names its catalog, else in its catalog's next version.
     */
    fun resolveCulture(tenant: Tenant, variantId: VariantId, catalogVersion: CatalogReleaseRef?): RenderCulture = resolveCulture(tenant, GetVariant(variantId = variantId, catalogVersion = catalogVersion).query()?.attributes ?: emptyMap())

    val applicationDefault: String get() = properties.defaultLocale

    private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
}

/** Which step of the locale chain answered: the variant's attribute, the tenant's default, or the installation's. */
enum class LocaleSource { VARIANT, TENANT, INSTALLATION }

/** A BCP-47 [tag] and the step of the chain it came from. */
data class ResolvedLocale(
    val tag: String,
    val source: LocaleSource,
) {
    /** Whether someone chose this language, for the template or for the tenant, rather than it being the installation's default. */
    val chosen: Boolean get() = source != LocaleSource.INSTALLATION
}
