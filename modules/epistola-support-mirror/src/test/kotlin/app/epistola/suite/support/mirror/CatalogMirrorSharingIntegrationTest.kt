// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.releases.ReleaseChannel
import app.epistola.suite.catalog.releases.ReleaseProvenance
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.Permission
import app.epistola.suite.security.PermissionDeniedException
import app.epistola.suite.security.TenantRole
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.testing.TestPrincipalUser
import app.epistola.suite.testing.exportReleased
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * What a tenant shares with the catalog mirror (ADR 0051): the consent, the per-catalog policies,
 * and the binding read model the reconcile sends, which must agree with the page's view of the
 * sharing rule. Support is off in this context: none of it calls out.
 */
class CatalogMirrorSharingIntegrationTest : CatalogMirrorTestBase() {

    @Test
    fun `nothing is shared before the tenant opts in, and opting in records the explicit choice, who and when`() {
        val tenant = createTenant("Mirror consent").id
        releasedCatalog(tenant, "letters", "1.0.0")

        val before = settings(tenant)
        assertThat(before.consent).isNull()
        assertThat(before.catalogs.single { it.catalog.key.value == "letters" }.sharing).isEqualTo(CatalogMirrorSharing.TENANT_NOT_SHARING)
        assertThat(bindings(tenant)).isEmpty()

        assertThatThrownBy { withMediator { OptInCatalogMirror(tenant, shareByDefault = true, consentVersion = "1999-01-01", connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() } }
            .isInstanceOf(CatalogMirrorConsentOutdatedException::class.java)
        assertThat(settings(tenant).consent).`as`("an outdated statement records nothing").isNull()

        optIn(tenant, shareByDefault = false)

        val consent = settings(tenant).consent!!
        assertThat(consent.shareByDefault).isFalse()
        assertThat(consent.consentVersion).isEqualTo(CatalogMirror.CONSENT_VERSION)
        assertThat(consent.optedInBy).isEqualTo(TestPrincipalUser.DISPLAY_NAME)
        assertThat(consent.optedInAt).isEqualTo(testClock.instant().atOffset(consent.optedInAt!!.offset))
        assertThat(consent.sharing).isTrue()
    }

    @Test
    fun `sharing every catalog shares those that inherit and those marked, Epistola's own included, never the withheld`() {
        val tenant = createTenant("Mirror share all").id
        val letters = releasedCatalog(tenant, "letters", "1.0.0", "1.1.0")
        val forms = releasedCatalog(tenant, "forms", "2.0.0")
        val secret = releasedCatalog(tenant, "secret", "1.0.0")
        optIn(tenant, shareByDefault = true)
        policy(secret, CatalogMirrorPolicy.NEVER)
        policy(forms, CatalogMirrorPolicy.SHARE)

        val entries = settings(tenant).catalogs.associateBy { it.catalog.key.value }
        assertThat(entries.getValue("letters").sharing).isEqualTo(CatalogMirrorSharing.SHARED)
        assertThat(entries.getValue("letters").policy).isEqualTo(CatalogMirrorPolicy.INHERIT)
        assertThat(entries.getValue("forms").sharing).isEqualTo(CatalogMirrorSharing.SHARED)
        assertThat(entries.getValue("secret").sharing).isEqualTo(CatalogMirrorSharing.WITHHELD)
        assertThat(entries.getValue(SYSTEM_CATALOG_KEY.value).sharing).isEqualTo(CatalogMirrorSharing.SHARED)
        assertThat(entries.getValue("letters").releases).isEqualTo(2)

        val shared = bindings(tenant)
        val system = testCatalog(tenant, SYSTEM_CATALOG_KEY)
        assertThat(shared.filterNot { it.catalog == system }.map { it.catalog.key.value to it.version })
            .containsExactlyInAnyOrder("letters" to "1.0.0", "letters" to "1.1.0", "forms" to "2.0.0")
        assertThat(shared.filter { it.catalog == system }.associate { it.version to it.contentHash }).`as`("every system catalog release")
            .isEqualTo(contentHashes(system))
        assertThat(shared.map { it.catalog.key.value }.toSet())
            .`as`("the page and the reconcile apply one rule (a shared catalog without releases has no bindings)")
            .isEqualTo(entries.values.filter { it.sharing == CatalogMirrorSharing.SHARED && it.releases > 0 }.map { it.catalog.key.value }.toSet())
        val hashes = contentHashes(letters)
        assertThat(shared.filter { it.catalog == letters }.associate { it.version to it.contentHash }).isEqualTo(hashes)
        assertThat(shared.first { it.catalog == letters }.registry).isEqualTo(withMediator { GetCatalog(letters).query()!!.registry })
    }

    @Test
    fun `sharing only marked catalogs shares those marked, and going back to inherit stops sharing it`() {
        val tenant = createTenant("Mirror share marked").id
        val letters = releasedCatalog(tenant, "letters", "1.0.0")
        releasedCatalog(tenant, "forms", "1.0.0")
        optIn(tenant, shareByDefault = false)

        assertThat(settings(tenant).catalogs.single { it.catalog == letters }.sharing).isEqualTo(CatalogMirrorSharing.NOT_MARKED)
        assertThat(bindings(tenant)).isEmpty()

        policy(letters, CatalogMirrorPolicy.SHARE)
        assertThat(bindings(tenant).map { it.catalog.key.value }).containsExactly("letters")

        policy(letters, CatalogMirrorPolicy.INHERIT)
        assertThat(bindings(tenant)).isEmpty()
        assertThat(settings(tenant).catalogs.single { it.catalog == letters }.policy).isEqualTo(CatalogMirrorPolicy.INHERIT)
    }

    @Test
    fun `a release whose dependency is never shared is withheld, and the page says why`() {
        val tenant = createTenant("Mirror bindings").id
        val shared = releasedCatalog(tenant, "shared", "1.0.0")
        val letters = releasedCatalog(tenant, "letters", "1.0.0")
        releaseDependingOn(letters, shared, "1.0.0", "1.1.0")
        val acceptance = environment(tenant, "acc", production = false)
        val production = environment(tenant, "prd", production = true)
        deploy(acceptance, letters, "1.1.0")
        deploy(production, letters, "1.1.0")
        deploy(production, letters, "1.0.0")
        optIn(tenant, shareByDefault = true)
        // Never share wins: letters 1.1.0 cannot be rendered without it, so it is not sent at all.
        policy(shared, CatalogMirrorPolicy.NEVER)

        val bindings = bindings(tenant).filterNot { it.catalog.key == SYSTEM_CATALOG_KEY }.associateBy { it.catalog.key.value to it.version }

        assertThat(bindings.keys).containsExactly("letters" to "1.0.0")
        assertThat(bindings.getValue("letters" to "1.0.0").deployments).containsExactly(MirrorDeployment("prd", true))
        val entry = settings(tenant).catalogs.single { it.catalog == letters }
        assertThat(entry.sharing).isEqualTo(CatalogMirrorSharing.SHARED)
        assertThat(entry.withheldReleases).isEqualTo(1)
        assertThat(entry.withheldBecause).containsExactly("${shared.namespace}/shared")
    }

    @Test
    fun `a shared release brings every release it depends on, all the way down, with their deployments and pins`() {
        val tenant = createTenant("Mirror dependencies").id
        val base = releasedCatalog(tenant, "base", "1.0.0")
        val mid = releasedCatalog(tenant, "mid", "1.0.0")
        releaseDependingOn(mid, base, "1.0.0", "1.1.0")
        val letters = releasedCatalog(tenant, "letters", "1.0.0")
        releaseDependingOn(letters, mid, "1.1.0", "2.0.0")
        val acceptance = environment(tenant, "acc", production = false)
        val production = environment(tenant, "prd", production = true)
        deploy(acceptance, letters, "2.0.0")
        deploy(production, letters, "2.0.0")
        // Only letters is marked; mid and base are not, and go because letters needs them.
        optIn(tenant, shareByDefault = false)
        policy(letters, CatalogMirrorPolicy.SHARE)

        val bindings = bindings(tenant).associateBy { it.catalog.key.value to it.version }

        assertThat(bindings.keys).containsExactlyInAnyOrder("letters" to "1.0.0", "letters" to "2.0.0", "mid" to "1.1.0", "base" to "1.0.0")
        val latest = bindings.getValue("letters" to "2.0.0")
        assertThat(latest.deployments).containsExactly(MirrorDeployment("acc", false), MirrorDeployment("prd", true))
        val pin = latest.dependencies.single()
        assertThat(pin.namespace to pin.key).isEqualTo(mid.namespace to "mid")
        assertThat(pin.version).isEqualTo("1.1.0")
        assertThat(pin.contentHash).isEqualTo(contentHashes(mid).getValue("1.1.0")).startsWith("v6:")
        assertThat(bindings.getValue("mid" to "1.1.0").contentHash).isEqualTo(contentHashes(mid).getValue("1.1.0"))
        assertThat(bindings.getValue("base" to "1.0.0").contentHash).isEqualTo(contentHashes(base).getValue("1.0.0"))
        val entries = settings(tenant).catalogs.associateBy { it.catalog.key.value }
        assertThat(entries.getValue("mid").sharing).isEqualTo(CatalogMirrorSharing.NOT_MARKED)
        assertThat(entries.getValue("mid").sentAsDependency).`as`("only the release letters pins").isEqualTo(1)
        assertThat(entries.getValue("base").sentAsDependency).isEqualTo(1)
        assertThat(entries.getValue("letters").withheldReleases).isZero()
    }

    @Test
    fun `drafts are never shared, and keyset pages walk every binding once`() {
        val tenant = createTenant("Mirror pages").id
        releasedCatalog(tenant, "letters", "1.0.0", "1.1.0", "1.2.0")
        releasedCatalog(tenant, "forms", "1.0.0", "2.0.0")
        // Each catalog has an open draft (its next version) besides its releases.
        optInOwnCatalogs(tenant)

        val all = bindings(tenant)
        val paged = bindings(tenant, pageSize = 2)

        assertThat(all).hasSize(5)
        assertThat(paged).containsExactlyElementsOf(all)
        assertThat(all.map { it.contentHash }).allMatch { it.startsWith("v6:") }
    }

    @Test
    fun `a received catalog can be shared or withheld like any other`() {
        val publisher = createTenant("Mirror publisher").id
        val tenant = createTenant("Mirror receiver").id
        releasedCatalog(publisher, "letters", "1.0.0")
        val archive = withMediator { mediator.exportReleased(testCatalog(publisher, "letters")) }
        withMediator { ImportCatalogZip(tenantKey = tenant, zipBytes = archive.zipBytes, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute() }
        val received = testCatalog(tenant, "letters")
        optIn(tenant, shareByDefault = false)

        policy(received, CatalogMirrorPolicy.SHARE)
        assertThat(bindings(tenant).map { it.catalog }).containsExactly(received)

        policy(received, CatalogMirrorPolicy.NEVER)
        assertThat(bindings(tenant)).isEmpty()
    }

    @Test
    fun `Epistola's own catalogs take a policy like any other`() {
        val tenant = createTenant("Mirror system").id
        optIn(tenant, shareByDefault = false)
        val system = testCatalog(tenant, SYSTEM_CATALOG_KEY)
        policy(system, CatalogMirrorPolicy.SHARE)
        assertThat(settings(tenant).catalogs.single { it.catalog == system }.sharing).isEqualTo(CatalogMirrorSharing.SHARED)
    }

    @Test
    fun `opting out shares nothing at once and leaves the withdrawal for the reconcile to confirm`() {
        val tenant = createTenant("Mirror opt out").id
        releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        assertThat(bindings(tenant)).hasSize(1)

        val result = withMediator { OptOutCatalogMirror(tenant, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }!!

        assertThat(result.sharing).isFalse()
        assertThat(bindings(tenant)).isEmpty()
        val consent = settings(tenant).consent!!
        assertThat(consent.withdrawalPending).isTrue()
        assertThat(settings(tenant).round?.syncRequestedAt).`as`("a withdrawal is scheduled at once").isNotNull()
        val tenants = withMediator { ListMirrorTenants(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        assertThat(tenants.withdrawalPending).contains(tenant)
        assertThat(tenants.sharing).doesNotContain(tenant)

        optIn(tenant, shareByDefault = false)
        assertThat(settings(tenant).consent!!.withdrawalPending).`as`("opting in again starts a new record").isFalse()
    }

    @Test
    fun `loosening needs integrations management, withholding needs catalog management`() {
        val tenant = createTenant("Mirror permissions").id
        val letters = releasedCatalog(tenant, "letters", "1.0.0")

        assertThat(SetCatalogMirrorPolicy(letters, CatalogMirrorPolicy.NEVER, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).permission).isEqualTo(Permission.CATALOG_MANAGE)
        assertThat(SetCatalogMirrorPolicy(letters, CatalogMirrorPolicy.SHARE, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).permission).isEqualTo(Permission.INTEGRATIONS_MANAGE)
        assertThat(SetCatalogMirrorPolicy(letters, CatalogMirrorPolicy.INHERIT, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).permission).isEqualTo(Permission.INTEGRATIONS_MANAGE)

        val publisher = member(tenant, TenantRole.CONTENT_PUBLISHER, TenantRole.CONTENT_AUTHOR, TenantRole.CONTENT_VIEWER)
        assertThatThrownBy { runAs(publisher) { OptInCatalogMirror(tenant, true, CatalogMirror.CONSENT_VERSION, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() } }
            .isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { runAs(publisher) { SetCatalogMirrorPolicy(letters, CatalogMirrorPolicy.NEVER, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() } }
            .isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { runAs(publisher) { GetCatalogMirrorSettings(tenant, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() } }
            .isInstanceOf(PermissionDeniedException::class.java)

        val admin = member(tenant, TenantRole.TENANT_ADMINISTRATOR)
        runAs(admin) { SetCatalogMirrorPolicy(letters, CatalogMirrorPolicy.NEVER, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        val seen = runAs(admin) { GetCatalogMirrorSettings(tenant, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        assertThat(seen.canChangeSharing).isTrue()
        assertThat(seen.canWithhold).isTrue()
    }

    @Test
    fun `the catalog list is ordered and paged in the database`() {
        val tenant = createTenant("Mirror paging").id
        releasedCatalog(tenant, "alpha", "1.0.0")
        releasedCatalog(tenant, "bravo", "1.0.0")

        val all = settings(tenant)
        val first = withMediator { GetCatalogMirrorSettings(tenant, offset = 0, limit = 1, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        val second = withMediator { GetCatalogMirrorSettings(tenant, offset = 1, limit = 1, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }

        assertThat(first.totalCatalogs).isEqualTo(all.catalogs.size).isGreaterThanOrEqualTo(3)
        assertThat(first.catalogs.single()).isEqualTo(all.catalogs[0])
        assertThat(second.catalogs.single()).isEqualTo(all.catalogs[1])
        assertThat(first.hasNext).isTrue()
        assertThat(first.hasPrevious).isFalse()
        assertThat(all.catalogs.map { it.catalog.namespace to it.catalog.key.value }).isSortedAccordingTo(compareBy({ it.first }, { it.second }))
    }

    private fun member(tenant: TenantKey, vararg roles: TenantRole) = EpistolaPrincipal(
        userId = TestPrincipalUser.ID,
        externalId = TestPrincipalUser.EXTERNAL_ID,
        email = TestPrincipalUser.EMAIL,
        displayName = TestPrincipalUser.DISPLAY_NAME,
        tenantMemberships = mapOf(tenant to roles.toSet()),
        currentTenantId = tenant,
    )
}
