package app.epistola.suite.tenants

import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.ThemeNotFoundException
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TenantCommandsTest : IntegrationTestBase() {
    @Test
    fun `CreateTenant creates tenant with name`() = fixture {
        whenever {
            createTenant("Acme Corp")
        }

        then {
            val tenant = result<Tenant>()
            assertThat(tenant.id).isNotNull()
            assertThat(tenant.name).isEqualTo("Acme Corp")
            assertThat(tenant.createdAt).isNotNull()
        }
    }

    @Test
    fun `CreateTenant does not assign a default theme`() = fixture {
        whenever {
            createTenant("No Theme Co")
        }

        then {
            val tenant = result<Tenant>()
            assertThat(tenant.defaultThemeKey)
                .describedAs("themes are optional — new tenants have no default theme")
                .isNull()
        }
    }

    @Test
    fun `DeleteTenant removes tenant by id`() = fixture {
        var tenantId: TenantKey? = null

        given {
            tenantId = tenant("To Delete").id
        }

        whenever {
            deleteTenant(tenantId!!)
        }

        then {
            assertThat(result<Boolean>()).isTrue()
        }
    }

    @Test
    fun `DeleteTenant returns false for non-existent id`() = fixture {
        whenever {
            deleteTenant(TenantKey.of("non-existent-tenant"))
        }

        then {
            assertThat(result<Boolean>()).isFalse()
        }
    }

    @Test
    fun `SetTenantDefaultTheme with null themeId clears the default`() = fixture {
        var tenant: Tenant? = null

        given {
            tenant = tenant("Clear Default Co")
            // Point the tenant at the bundled `system/default` theme first so
            // the clear has something concrete to undo.
            SetTenantDefaultTheme(
                tenantId = tenant!!.id,
                themeId = ThemeKey.of("default"),
                catalogKey = SYSTEM_CATALOG_KEY,
            ).execute()
        }

        whenever {
            SetTenantDefaultTheme(tenantId = tenant!!.id, themeId = null).execute()
        }

        then {
            val updated = result<Tenant>()
            assertThat(updated.defaultThemeKey).isNull()
            assertThat(updated.defaultThemeCatalogKey).isNull()
        }
    }

    @Test
    fun `SetTenantDefaultTheme rejects a themeId that does not exist in the given catalog`() = fixture {
        var tenant: Tenant? = null

        given {
            tenant = tenant("Bad Theme Co")
        }

        then {
            assertThatThrownBy {
                SetTenantDefaultTheme(
                    tenantId = tenant!!.id,
                    themeId = ThemeKey.of("nonexistent"),
                    catalogKey = SYSTEM_CATALOG_KEY,
                ).execute()
            }.isInstanceOf(ThemeNotFoundException::class.java)
        }
    }

    @Test
    fun `DeleteTheme succeeds after the tenant default has been cleared`() = fixture {
        var tenant: Tenant? = null
        var themeId: ThemeId? = null

        given {
            tenant = tenant("Delete Theme Co")
            val catalogId = CatalogId.default(TenantId(tenant!!.id))
            val theme = CreateTheme(
                id = ThemeId(ThemeKey.of("custom"), catalogId),
                name = "Custom",
            ).execute()
            themeId = ThemeId(theme.id, catalogId)
            SetTenantDefaultTheme(
                tenantId = tenant!!.id,
                themeId = theme.id,
                catalogKey = catalogId.key,
            ).execute()
            // Clearing the default unblocks DeleteTheme — the only remaining
            // guard is ThemeInUseException for the active tenant default.
            SetTenantDefaultTheme(tenantId = tenant!!.id, themeId = null).execute()
        }

        whenever {
            DeleteTheme(id = themeId!!).execute()
        }

        then {
            assertThat(result<Boolean>()).isTrue()
        }
    }
}
