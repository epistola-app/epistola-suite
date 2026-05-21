package app.epistola.suite.i18n

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
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
    fun resolve(tenant: Tenant, variantAttributes: Map<String, String>): String = variantAttributes["system.locale"].nullIfBlank()
        ?: variantAttributes["locale"].nullIfBlank()
        ?: tenant.defaultLocale
        ?: properties.defaultLocale

    /** [Locale] flavour of [resolve]`(tenant, variantAttributes)`. */
    fun resolveLocale(tenant: Tenant, variantAttributes: Map<String, String>): Locale = Locale.forLanguageTag(resolve(tenant, variantAttributes))

    /** [Locale] flavour of [resolve]`(tenant)`. */
    fun resolveLocale(tenant: Tenant): Locale = Locale.forLanguageTag(resolve(tenant))

    val applicationDefault: String get() = properties.defaultLocale

    private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
}
