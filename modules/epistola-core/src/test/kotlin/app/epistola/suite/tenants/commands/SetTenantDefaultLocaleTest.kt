package app.epistola.suite.tenants.commands

import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SetTenantDefaultLocaleTest : IntegrationTestBase() {
    @Test
    fun `setting a valid locale persists the override`() {
        val tenant = createTenant("Locale Acme")

        val updated = withMediator {
            SetTenantDefaultLocale(tenantId = tenant.id, locale = "nl-NL").execute()
        }

        assertThat(updated.defaultLocale).isEqualTo("nl-NL")
        val reloaded = withMediator { GetTenant(tenant.id).query() }
        assertThat(reloaded?.defaultLocale).isEqualTo("nl-NL")
    }

    @Test
    fun `setting an unknown locale throws InvalidLocaleException`() {
        val tenant = createTenant("Locale Bogus")

        assertThatThrownBy {
            withMediator {
                SetTenantDefaultLocale(tenantId = tenant.id, locale = "xx-ZZ").execute()
            }
        }.isInstanceOf(InvalidLocaleException::class.java)

        val reloaded = withMediator { GetTenant(tenant.id).query() }
        assertThat(reloaded?.defaultLocale).isNull()
    }

    @Test
    fun `clearing the override stores null`() {
        val tenant = createTenant("Locale Clear")
        withMediator {
            SetTenantDefaultLocale(tenantId = tenant.id, locale = "nl-NL").execute()
        }

        val cleared = withMediator {
            SetTenantDefaultLocale(tenantId = tenant.id, locale = null).execute()
        }

        assertThat(cleared.defaultLocale).isNull()
        val reloaded = withMediator { GetTenant(tenant.id).query() }
        assertThat(reloaded?.defaultLocale).isNull()
    }
}
