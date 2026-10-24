// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.namespaces

import app.epistola.suite.catalog.commands.ChangeCatalogNamespace
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.catalog.snapshot.BuildTenantSnapshot
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTenantCounter
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A new catalog is named in a namespace its tenant holds, or under `local` (ADR 0034, sections 3 and
 * 7), and only the holder of a namespace releases in it (section 2). Receiving needs no grant.
 */
class CatalogNamingTest : IntegrationTestBase() {

    private val acme = NamespaceName("gemeente-x.nl", "acme")
    private val hr = NamespaceName("gemeente-x.nl", "acme-hr")
    private val letters = CatalogKey.of("letters")

    private fun tenant(name: String, vararg namespaces: NamespaceName): TenantKey = withMediator {
        CreateTenant(TenantKey.of("nsname-${TestTenantCounter.next("nsname")}"), name, namespaces.toList()).execute().id
    }

    private fun refusal(block: () -> Unit): ValidationException {
        var caught: ValidationException? = null
        assertThatThrownBy { block() }.isInstanceOfSatisfying(ValidationException::class.java) { caught = it }
        return caught!!
    }

    /** A theme in [catalog] of [tenant], marked ready and released as [version]. */
    private fun release(tenant: TenantKey, catalog: CatalogKey, version: String, theme: String = "brand") {
        CreateTheme(ThemeId(ThemeKey.of(theme), testCatalog(TenantId(tenant), catalog)), "Theme $theme").execute()
        MarkCatalogReady(testCatalog(tenant, catalog)).execute()
        ReleaseCatalogDraft(catalog = testCatalog(tenant, catalog), version = version).execute()
    }

    @Test
    fun `a new catalog is named in the namespace the tenant holds, by default the one it holds`() {
        val tenant = tenant("Naming one", acme)

        withMediator {
            val named = CreateCatalog(tenant, letters, "Letters").execute()
            assertThat(named.registry to named.namespace).isEqualTo("gemeente-x.nl" to "acme")

            val byNamespace = CreateCatalog(tenant, CatalogKey.of("forms"), "Forms", namespace = "acme").execute()
            assertThat(byNamespace.registry).`as`("a namespace alone is found under the authority it is held under").isEqualTo("gemeente-x.nl")
        }
    }

    @Test
    fun `a namespace the tenant does not hold is refused, and several held ask which`() {
        val tenant = tenant("Naming several", acme, hr)

        withMediator {
            val notHeld = refusal { CreateCatalog(tenant, letters, "Letters", namespace = "other", registry = "gemeente-x.nl").execute() }
            assertThat(notHeld.code).isEqualTo(ValidationCode.CATALOG_NAMESPACE_NOT_HELD)
            assertThat(notHeld.message).isEqualTo("This tenant does not hold the namespace other (gemeente-x.nl); an administrator can grant it.")

            val ambiguous = refusal { CreateCatalog(tenant, letters, "Letters").execute() }
            assertThat(ambiguous.code).isEqualTo(ValidationCode.CATALOG_NAMESPACE_REQUIRED)
            assertThat(ambiguous.message).isEqualTo("This tenant holds several namespaces (gemeente-x.nl/acme, gemeente-x.nl/acme-hr): choose the one to name the catalog in.")

            val chosen = CreateCatalog(tenant, letters, "Letters", namespace = "acme-hr").execute()
            assertThat(chosen.registry to chosen.namespace).isEqualTo("gemeente-x.nl" to "acme-hr")
        }
    }

    @Test
    fun `a tenant holding no namespace names a catalog under local, with a namespace it picks, and nothing else`() {
        val tenant = tenant("Naming none")

        withMediator {
            val none = refusal { CreateCatalog(tenant, letters, "Letters").execute() }
            assertThat(none.code).isEqualTo(ValidationCode.CATALOG_NAMESPACE_REQUIRED)
            assertThat(none.message).isEqualTo("This tenant holds no namespace. Name the catalog under local, with a namespace you choose, or ask an administrator to grant one.")
            assertThat(refusal { CreateCatalog(tenant, letters, "Letters", namespace = tenant.value).execute() }.code)
                .`as`("its own key is not a namespace it holds").isEqualTo(ValidationCode.CATALOG_NAMESPACE_NOT_HELD)
            assertThat(refusal { CreateCatalog(tenant, letters, "Letters", registry = LocalAuthority.NAME).execute() }.code).isEqualTo(ValidationCode.CATALOG_NAMESPACE_REQUIRED)

            val local = CreateCatalog(tenant, letters, "Letters", registry = LocalAuthority.NAME, namespace = "personnel").execute()
            assertThat(local.registry to local.namespace).isEqualTo("local" to "personnel")
        }
    }

    @Test
    fun `a local namespace is not one the tenant holds under another authority`() {
        val tenant = tenant("Naming local taken", acme)

        withMediator {
            val taken = refusal { CreateCatalog(tenant, letters, "Letters", registry = LocalAuthority.NAME, namespace = "acme").execute() }
            assertThat(taken.code).isEqualTo(ValidationCode.CATALOG_NAMESPACE_TAKEN)
            assertThat(taken.message).contains("holds the namespace acme under gemeente-x.nl")
        }
    }

    @Test
    fun `releasing needs the namespace, so a received catalog is released only once its tenant holds it`() {
        val author = tenant("Release author", acme)
        val receiver = tenant("Release receiver", hr)

        withMediator {
            CreateCatalog(author, letters, "Letters").execute()
            release(author, letters, "1.0.0")
            // Receiving needs no grant.
            ImportCatalogZip(receiver, ExportCatalogZip(testCatalog(author, letters), "1.0.0").execute().zipBytes).execute()

            CreateTheme(ThemeId(ThemeKey.of("fix"), testCatalog(TenantId(receiver), letters)), "Fix").execute()
            MarkCatalogReady(testCatalog(receiver, letters)).execute()
            val refused = refusal { ReleaseCatalogDraft(catalog = testCatalog(receiver, letters), version = "1.1.0").execute() }
            assertThat(refused.code).isEqualTo(ValidationCode.CATALOG_NAMESPACE_NOT_HELD)
            assertThat(refused.message).isEqualTo("This tenant does not hold the namespace acme (gemeente-x.nl); an administrator can grant it.")
            assertThat(GetCatalogNamespaceHolding(testCatalog(receiver, letters)).query())
                .isEqualTo(CatalogNamespaceHolding(acme, held = false))

            GrantTenantNamespace(receiver, "gemeente-x.nl", "acme").execute()
            assertThat(GetCatalogNamespaceHolding(testCatalog(receiver, letters)).query().refusal).isNull()
            ReleaseCatalogDraft(catalog = testCatalog(receiver, letters), version = "1.1.0").execute()
            assertThat(ListCatalogReleaseSummaries(testCatalog(receiver, letters)).query().map { it.version }).containsExactly("1.1.0", "1.0.0")
        }
    }

    @Test
    fun `a catalog under local is released without a grant, exported, received elsewhere, copied into a draft and snapshotted`() {
        val tenant = tenant("Local release")
        val elsewhere = tenant("Local elsewhere")

        withMediator {
            CreateCatalog(tenant, letters, "Letters", registry = LocalAuthority.NAME, namespace = "personnel").execute()
            release(tenant, letters, "1.0.0")
            assertThat(ListCatalogReleaseSummaries(testCatalog(tenant, letters)).query().map { it.version }).containsExactly("1.0.0")

            // The catalog format carries local (contract feature/v2 0fe54cc): the archive names it.
            val archive = ExportCatalogZip(testCatalog(tenant, letters), "1.0.0").execute().zipBytes
            ImportCatalogZip(elsewhere, archive).execute()
            val received = GetCatalog(testCatalog(elsewhere, letters)).query()!!
            assertThat(received.registry to received.namespace).isEqualTo("local" to "personnel")

            CreateCatalogDraft(testCatalog(tenant, letters), "Fix", from = "1.0.0").execute()
            assertThat(ListCatalogReleases(testCatalog(tenant, letters)).query().map { it.name }).contains("Fix")
            assertThat(BuildTenantSnapshot(tenant).execute().catalogCount).`as`("its default and letters, both local").isEqualTo(2)
        }
    }

    @Test
    fun `a catalog moves only into a namespace its tenant holds, or within local`() {
        val tenant = tenant("Change namespace", acme)

        withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            assertThat(refusal { ChangeCatalogNamespace(testCatalog(tenant, letters), "acme-hr").execute() }.code).isEqualTo(ValidationCode.CATALOG_NAMESPACE_NOT_HELD)

            GrantTenantNamespace(tenant, "gemeente-x.nl", "acme-hr").execute()
            assertThat(ChangeCatalogNamespace(testCatalog(tenant, letters), "acme-hr").execute().namespace).isEqualTo("acme-hr")

            val local = CatalogKey.of("notes")
            CreateCatalog(tenant, local, "Notes", registry = LocalAuthority.NAME, namespace = "personnel").execute()
            assertThat(ChangeCatalogNamespace(testCatalog(tenant, local), "finance").execute().let { it.registry to it.namespace }).isEqualTo("local" to "finance")
            assertThat(refusal { ChangeCatalogNamespace(testCatalog(tenant, local), "acme").execute() }.code).isEqualTo(ValidationCode.CATALOG_NAMESPACE_TAKEN)
        }
    }
}
