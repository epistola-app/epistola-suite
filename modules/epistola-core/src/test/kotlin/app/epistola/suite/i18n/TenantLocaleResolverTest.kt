package app.epistola.suite.i18n

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

@Tag("unit")
class TenantLocaleResolverTest {
    private val resolver = TenantLocaleResolver(LocaleProperties(defaultLocale = "en-US"))

    @Test
    fun `tenant override wins over the application default`() {
        val tenant = tenant(defaultLocale = "nl-NL")
        assertThat(resolver.resolve(tenant)).isEqualTo("nl-NL")
    }

    @Test
    fun `null override falls back to the application default`() {
        val tenant = tenant(defaultLocale = null)
        assertThat(resolver.resolve(tenant)).isEqualTo("en-US")
    }

    @Test
    fun `applicationDefault exposes the configured property value`() {
        assertThat(resolver.applicationDefault).isEqualTo("en-US")
    }

    @Test
    fun `variant attribute 'system_locale' wins over both tenant and app default`() {
        val tenant = tenant(defaultLocale = "nl-NL")
        val attrs = mapOf("system.locale" to "fr-FR")
        assertThat(resolver.resolve(tenant, attrs)).isEqualTo("fr-FR")
    }

    @Test
    fun `bare 'locale' attribute is honoured when catalog-qualified one is absent`() {
        val tenant = tenant(defaultLocale = "nl-NL")
        val attrs = mapOf("locale" to "de-DE")
        assertThat(resolver.resolve(tenant, attrs)).isEqualTo("de-DE")
    }

    @Test
    fun `catalog-qualified 'system_locale' beats bare 'locale' when both are present`() {
        val tenant = tenant(defaultLocale = "nl-NL")
        val attrs = mapOf("locale" to "de-DE", "system.locale" to "fr-FR")
        assertThat(resolver.resolve(tenant, attrs)).isEqualTo("fr-FR")
    }

    @Test
    fun `blank variant attribute falls through to tenant default`() {
        val tenant = tenant(defaultLocale = "nl-NL")
        val attrs = mapOf("system.locale" to "  ", "locale" to "")
        assertThat(resolver.resolve(tenant, attrs)).isEqualTo("nl-NL")
    }

    @Test
    fun `empty variant attributes fall back to tenant default`() {
        val tenant = tenant(defaultLocale = "nl-NL")
        assertThat(resolver.resolve(tenant, emptyMap())).isEqualTo("nl-NL")
    }

    @Test
    fun `variant attributes empty AND tenant null falls all the way to app default`() {
        val tenant = tenant(defaultLocale = null)
        assertThat(resolver.resolve(tenant, emptyMap())).isEqualTo("en-US")
    }

    @Test
    fun `resolveLocale returns a Locale instance for the resolved tag`() {
        val tenant = tenant(defaultLocale = null)
        val attrs = mapOf("system.locale" to "nl-NL")
        val locale = resolver.resolveLocale(tenant, attrs)
        assertThat(locale.toLanguageTag()).isEqualTo("nl-NL")
    }

    private fun tenant(defaultLocale: String?) = Tenant(
        id = TenantKey.of("tenant-1"),
        name = "Tenant One",
        defaultThemeKey = null,
        defaultLocale = defaultLocale,
        createdAt = OffsetDateTime.now(),
    )
}
