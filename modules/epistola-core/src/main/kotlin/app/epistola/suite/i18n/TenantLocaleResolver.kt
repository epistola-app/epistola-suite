package app.epistola.suite.i18n

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.stereotype.Component

/**
 * Resolves the effective default locale for a tenant. A tenant's own override
 * wins; when it is null, the application-wide default
 * (`epistola.i18n.default-locale`) applies.
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

    val applicationDefault: String get() = properties.defaultLocale
}
