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

    private fun tenant(defaultLocale: String?) = Tenant(
        id = TenantKey.of("tenant-1"),
        name = "Tenant One",
        defaultThemeKey = null,
        defaultLocale = defaultLocale,
        createdAt = OffsetDateTime.now(),
    )
}
