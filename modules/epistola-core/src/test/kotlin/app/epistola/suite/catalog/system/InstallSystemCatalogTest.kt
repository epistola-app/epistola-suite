package app.epistola.suite.catalog.system

import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.variants.validateAttributes
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Coverage for [InstallSystemCatalog] and the bundled `system` catalog payload.
 *
 * `createTenant(...)` already triggers `CreateTenant`, which calls
 * `InstallSystemCatalog`. These tests assert (a) that newly-created tenants
 * end up with the bundled resources installed and wired up, and (b) that the
 * installer is idempotent — re-running on the same tenant + version is a
 * no-op that returns `ALREADY_CURRENT`.
 */
class InstallSystemCatalogTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `new tenant has system catalog installed by CreateTenant`() {
        val tenant = createTenant("Sys1")

        withMediator {
            val catalog = GetCatalog(tenant.id, SYSTEM_CATALOG_KEY).query()
            assertThat(catalog).isNotNull()
            assertThat(catalog!!.type).isEqualTo(CatalogType.SUBSCRIBED)
            assertThat(catalog.sourceUrl).isEqualTo(SYSTEM_CATALOG_URL)
            assertThat(catalog.installedReleaseVersion).isNotBlank()
        }
    }

    @Test
    fun `system catalog brings the three reserved code lists`() {
        val tenant = createTenant("Sys2")
        val tenantId = TenantId(tenant.id)
        val systemCatalogId = CatalogId(SYSTEM_CATALOG_KEY, tenantId)

        withMediator {
            for (slug in listOf("bcp-47", "iso-639-1", "iso-3166-1-alpha2")) {
                val id = CodeListId(CodeListKey.of(slug), systemCatalogId)
                val codeList = GetCodeList(id).query()
                assertThat(codeList).withFailMessage("missing code list $slug").isNotNull()

                val entries = ListCodeListEntries(id).query()
                assertThat(entries).withFailMessage("code list $slug has no entries").isNotEmpty()
            }
        }
    }

    @Test
    fun `system catalog binds reserved attributes to their canonical code lists`() {
        val tenant = createTenant("Sys3")
        val tenantId = TenantId(tenant.id)
        val systemCatalogId = CatalogId(SYSTEM_CATALOG_KEY, tenantId)

        withMediator {
            val expected = mapOf(
                "locale" to "bcp-47",
                "language" to "iso-639-1",
                "country" to "iso-3166-1-alpha2",
            )
            for ((attrSlug, codeListSlug) in expected) {
                val attr = GetAttributeDefinition(
                    AttributeId(AttributeKey.of(attrSlug), systemCatalogId),
                ).query()
                assertThat(attr)
                    .withFailMessage("attribute $attrSlug missing from system catalog")
                    .isNotNull()
                assertThat(attr!!.codeListSlug?.value)
                    .withFailMessage("attribute $attrSlug not bound to $codeListSlug")
                    .isEqualTo(codeListSlug)
                assertThat(attr.codeListCatalogKey?.value).isEqualTo(SYSTEM_CATALOG_KEY.value)
                assertThat(attr.allowedValues).isEmpty()
            }
        }
    }

    @Test
    fun `re-running install on the same version is a no-op`() {
        val tenant = createTenant("Sys4")

        withMediator {
            val result = InstallSystemCatalog(tenantKey = tenant.id).execute()
            assertThat(result.status).isEqualTo(SystemCatalogStatus.ALREADY_CURRENT)
        }
    }

    @Test
    fun `installer upgrades when installed version is older than bundled version`() {
        // We can't easily mutate the bundled manifest mid-test, so simulate a
        // "tenant content has drifted from the bundle" state by stamping a
        // stale fingerprint into the catalogs row (upgrade detection is
        // fingerprint-based, not version-string-based). If the upgrade path is
        // wired correctly, the installer should call `UpgradeCatalog` and
        // report `UPGRADED`, bringing the row back to the current bundle.
        val tenant = createTenant("SysUpgrade")

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalogs
                SET installed_release_version = '0', installed_fingerprint = 'stale-fingerprint'
                WHERE tenant_key = :tenantKey AND id = 'system'
                """,
            )
                .bind("tenantKey", tenant.id)
                .execute()
        }

        withMediator {
            val result = InstallSystemCatalog(tenantKey = tenant.id).execute()
            assertThat(result.status).isEqualTo(SystemCatalogStatus.UPGRADED)
            assertThat(result.installedVersion).isNotBlank()

            // After the upgrade the row reflects the bundled version again.
            val catalog = GetCatalog(tenant.id, SYSTEM_CATALOG_KEY).query()
            assertThat(catalog!!.installedReleaseVersion).isEqualTo(result.installedVersion)
        }
    }

    @Test
    fun `variant validation accepts a value present in system code list`() {
        val tenant = createTenant("SysVal1")

        withMediator {
            assertThatCode {
                validateAttributes(TenantId(tenant.id), mapOf("language" to "en"))
            }.doesNotThrowAnyException()
        }
    }

    @Test
    fun `variant validation rejects a value absent from system code list`() {
        val tenant = createTenant("SysVal2")

        withMediator {
            assertThatThrownBy {
                validateAttributes(TenantId(tenant.id), mapOf("language" to "not-a-real-code"))
            }.isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("Not a member of code list 'system/iso-639-1'")
        }
    }

    @Test
    fun `catalog-qualified key disambiguates same-slug attributes across catalogs`() {
        // Reproduces the silent-collision scenario: an authored catalog
        // defines a `language` attribute with inline values (e.g. demo's
        // 'nl'/'en'/'de'/'fr'), and the system catalog provides its own
        // `language` bound to ISO 639-1. The qualified-key form lets a
        // variant pick exactly one of the two without depending on
        // associateBy's pick.
        val tenant = createTenant("SysVal3")

        withMediator {
            // The tenant's default catalog gains its own `language` attribute
            // with inline values that do NOT overlap with ISO 639-1, so we
            // can distinguish which definition the validator picked from
            // which value passes.
            app.epistola.suite.attributes.commands.CreateAttributeDefinition(
                id = AttributeId(
                    AttributeKey.of("language"),
                    CatalogId(app.epistola.suite.common.ids.CatalogKey.of("default"), TenantId(tenant.id)),
                ),
                displayName = "Language (custom)",
                allowedValues = listOf("dutch", "english"),
            ).execute()

            // Bare slug — `associateBy` picks one definition non-deterministically.
            // Just assert it works without inspecting which. (See the dotted
            // form for explicit selection.)

            // Qualified key explicitly targets the system definition (ISO
            // 639-1): "en" is in the list, "english" is not.
            assertThatCode {
                validateAttributes(TenantId(tenant.id), mapOf("system.language" to "en"))
            }.doesNotThrowAnyException()

            assertThatThrownBy {
                validateAttributes(TenantId(tenant.id), mapOf("system.language" to "english"))
            }.isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("Not a member of code list 'system/iso-639-1'")

            // Qualified key targets the demo-style custom definition (inline
            // allowedValues): the inverse — "english" passes, "en" fails.
            assertThatCode {
                validateAttributes(TenantId(tenant.id), mapOf("default.language" to "english"))
            }.doesNotThrowAnyException()

            assertThatThrownBy {
                validateAttributes(TenantId(tenant.id), mapOf("default.language" to "en"))
            }.isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("Allowed values")
        }
    }
}
