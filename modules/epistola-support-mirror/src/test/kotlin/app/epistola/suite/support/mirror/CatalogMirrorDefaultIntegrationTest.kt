// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformAccessDeniedException
import app.epistola.suite.security.TenantRole
import app.epistola.suite.support.SupportProperties
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.testing.TestPrincipalUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired

/**
 * The installation's default for the catalog mirror: it applies directly to every tenant without a
 * choice of its own, recorded as the opt-in of the installation administrator who set it, and a
 * tenant's own choice wins until it follows the default again.
 */
@Isolated("The default applies to every tenant in the database")
class CatalogMirrorDefaultIntegrationTest : CatalogMirrorTestBase() {
    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var supportConnections: app.epistola.suite.support.connection.SupportConnections

    @AfterEach
    fun resetDefault() {
        withMediator { ClearCatalogMirrorDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        // Which configured preset was applied is bookkeeping no command writes or clears.
        jdbi.useHandle<Exception> { it.createUpdate("DELETE FROM app_metadata WHERE key = :key").bind("key", ApplyConfiguredCatalogMirrorDefault.APPLIED_KEY).execute() }
    }

    @Autowired
    private lateinit var jdbi: Jdbi

    private fun consent(tenant: app.epistola.suite.common.ids.TenantKey) = settings(tenant).consent

    @Test
    fun `the default opts in every tenant without a choice of its own, as agreed by who set it`() {
        val following = createTenant("Mirror Default Following").id
        val saidNo = createTenant("Mirror Default No").id
        withMediator { OptOutCatalogMirror(saidNo, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        val marked = createTenant("Mirror Default Own").id
        optIn(marked, shareByDefault = false)

        withMediator { SetCatalogMirrorDefault(CatalogMirrorDefaultChoice.ALL, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        val followed = consent(following)!!
        assertThat(followed.sharing).isTrue()
        assertThat(followed.shareByDefault).isTrue()
        assertThat(followed.followsDefault).isTrue()
        assertThat(followed.optedInBy).isEqualTo(TestPrincipalUser.DISPLAY_NAME)
        assertThat(followed.consentVersion).isEqualTo(CatalogMirror.CONSENT_VERSION)
        assertThat(consent(saidNo)!!.sharing).`as`("a tenant's own no wins").isFalse()
        assertThat(consent(marked)!!.shareByDefault).`as`("its own choice of marked catalogs stays").isFalse()
        assertThat(settings(following).installationDefault.choice).isEqualTo(CatalogMirrorDefaultChoice.ALL)
        assertThat(settings(following).installationDefault.setBy).isEqualTo(TestPrincipalUser.DISPLAY_NAME)
    }

    @Test
    fun `a tenant created later follows the default at once`() {
        withMediator { SetCatalogMirrorDefault(CatalogMirrorDefaultChoice.MARKED, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        val created = createTenant("Mirror Default Later").id

        val consent = consent(created)!!
        assertThat(consent.sharing).isTrue()
        assertThat(consent.shareByDefault).isFalse()
        assertThat(consent.followsDefault).isTrue()
    }

    @Test
    fun `turning the default off opts out only the tenants that follow it`() {
        val following = createTenant("Mirror Default Off Following").id
        val own = createTenant("Mirror Default Off Own").id
        optIn(own, shareByDefault = true)
        withMediator { SetCatalogMirrorDefault(CatalogMirrorDefaultChoice.ALL, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        withMediator { SetCatalogMirrorDefault(CatalogMirrorDefaultChoice.OFF, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        val followed = consent(following)!!
        assertThat(followed.sharing).isFalse()
        assertThat(followed.withdrawalPending).`as`("the reconcile confirms the withdrawal").isTrue()
        assertThat(consent(own)!!.sharing).`as`("its own opt-in stays").isTrue()
    }

    @Test
    fun `a tenant's own choice overrides the default until it follows the default again`() {
        val tenant = createTenant("Mirror Default Override").id
        withMediator { SetCatalogMirrorDefault(CatalogMirrorDefaultChoice.ALL, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        withMediator { OptOutCatalogMirror(tenant, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        withMediator { SetCatalogMirrorDefault(CatalogMirrorDefaultChoice.MARKED, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        assertThat(consent(tenant)!!.sharing).isFalse()

        withMediator { FollowCatalogMirrorDefault(tenant, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        val followed = consent(tenant)!!
        assertThat(followed.sharing).isTrue()
        assertThat(followed.shareByDefault).isFalse()
        assertThat(followed.followsDefault).isTrue()
    }

    @Test
    fun `a tenant that says no without ever sharing has nothing to withdraw`() {
        val tenant = createTenant("Mirror Default Never").id

        withMediator { OptOutCatalogMirror(tenant, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        val consent = consent(tenant)!!
        assertThat(consent.sharing).isFalse()
        assertThat(consent.followsDefault).isFalse()
        assertThat(consent.withdrawalPending).isFalse()
    }

    @Test
    fun `clearing the preset opts out the tenants that followed it`() {
        val following = createTenant("Mirror Default Cleared").id
        withMediator { SetCatalogMirrorDefault(CatalogMirrorDefaultChoice.ALL, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        withMediator { ClearCatalogMirrorDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        assertThat(consent(following)!!.sharing).isFalse()
        assertThat(consent(following)!!.followsDefault).isTrue()
        assertThat(withMediator { GetCatalogMirrorDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.isSet).isFalse()
    }

    @Test
    fun `the configured preset applies at startup only while none is stored`() {
        val following = createTenant("Mirror Default Configured").id
        val configured = ApplyConfiguredCatalogMirrorDefault(jdbi, SupportProperties(enabled = true, tenantDefaults = SupportProperties.TenantDefaults(mirror = "marked")), privacy, supportConnections)

        configured.apply()

        val default = withMediator { GetCatalogMirrorDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        assertThat(default.choice).isEqualTo(CatalogMirrorDefaultChoice.MARKED)
        assertThat(default.fromConfiguration).isTrue()
        assertThat(consent(following)!!.sharing).isTrue()
        assertThat(consent(following)!!.shareByDefault).isFalse()

        withMediator { SetCatalogMirrorDefault(CatalogMirrorDefaultChoice.OFF, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        configured.apply()
        assertThat(withMediator { GetCatalogMirrorDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.choice).`as`("a saved preset wins").isEqualTo(CatalogMirrorDefaultChoice.OFF)
    }

    @Autowired
    private lateinit var objectMapper: tools.jackson.databind.ObjectMapper

    @Autowired
    private lateinit var cipher: app.epistola.suite.crypto.CredentialCipher

    private val namedConfiguration = SupportProperties(enabled = true, tenantDefaults = SupportProperties.TenantDefaults(mirror = "all"), privacy = SupportProperties.Privacy(tenantIdentity = "named"))

    /** The metadata store, switched to failing as an unreachable database does. */
    private inner class Flaky : app.epistola.suite.metadata.AppMetadataService(jdbi, objectMapper, cipher) {
        @Volatile var down = false

        override fun get(key: String): tools.jackson.databind.JsonNode? = if (down) error("the database is unreachable") else super.get(key)
    }

    @Test
    fun `a configured preset an administrator cleared is not applied again at the next start`() {
        val following = createTenant("Mirror Default Cleared Configured").id
        ApplyConfiguredCatalogMirrorDefault(jdbi, namedConfiguration, privacy, supportConnections).apply()
        withMediator { ClearCatalogMirrorDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        // The next start, with the same configuration.
        ApplyConfiguredCatalogMirrorDefault(jdbi, namedConfiguration, privacy, supportConnections).apply()

        assertThat(withMediator { GetCatalogMirrorDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.isSet).isFalse()
        assertThat(consent(following)!!.sharing).isFalse()
    }

    @Test
    fun `the configured preset applies at startup also before this node read the privacy settings`() {
        val following = createTenant("Mirror Default Unread Privacy").id
        val unread = app.epistola.suite.support.privacy.SupportPrivacy(Flaky(), namedConfiguration, jdbi, objectMapper)

        ApplyConfiguredCatalogMirrorDefault(jdbi, namedConfiguration, unread, supportConnections).apply()

        assertThat(withMediator { GetCatalogMirrorDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.choice).isEqualTo(CatalogMirrorDefaultChoice.ALL)
        assertThat(consent(following)!!.sharing).isTrue()
    }

    @Test
    fun `a configured preset that changes takes effect at the next start, while nobody saved one`() {
        val following = createTenant("Mirror Default Configured Changed").id
        ApplyConfiguredCatalogMirrorDefault(jdbi, namedConfiguration, privacy, supportConnections).apply()
        assertThat(consent(following)!!.sharing).isTrue()

        // The operator turns the mirror preset off in the configuration and restarts.
        val off = SupportProperties(enabled = true, tenantDefaults = SupportProperties.TenantDefaults(mirror = "off"), privacy = SupportProperties.Privacy(tenantIdentity = "named"))
        ApplyConfiguredCatalogMirrorDefault(jdbi, off, privacy, supportConnections).apply()

        assertThat(withMediator { GetCatalogMirrorDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.choice).isEqualTo(CatalogMirrorDefaultChoice.OFF)
        assertThat(consent(following)!!.sharing).`as`("its following tenants stop sharing").isFalse()
    }

    @Test
    fun `a startup that cannot read the privacy settings forgets no tenant's choice`() {
        val tenant = createTenant("Mirror Default Startup Unreadable").id
        optIn(tenant, shareByDefault = true)
        val unreadable = app.epistola.suite.support.privacy.SupportPrivacy(Flaky().apply { down = true }, namedConfiguration, jdbi, objectMapper)

        CatalogMirrorConfidentiality(unreadable, jdbi).withdrawWhenConfidential()

        assertThat(consent(tenant)!!.sharing).isTrue()
    }

    @Test
    fun `disconnecting forgets every tenant's choice, the preset and the reconcile state, and keeps catalog policies`() {
        val own = createTenant("Mirror Forget Own").id
        optIn(own, shareByDefault = true)
        val catalog = releasedCatalog(own, "forget-never", "1.0.0")
        policy(catalog, CatalogMirrorPolicy.NEVER)
        val following = createTenant("Mirror Forget Following").id
        withMediator { SetCatalogMirrorDefault(CatalogMirrorDefaultChoice.ALL, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        withMediator { RequestCatalogMirrorSync(own, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        val forgotten = withMediator { ForgetCatalogMirrorSharing(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        assertThat(forgotten.tenants).isGreaterThanOrEqualTo(2)
        assertThat(consent(own)).isNull()
        assertThat(consent(following)).isNull()
        assertThat(settings(own).round).`as`("where its reconcile stood is gone").isNull()
        assertThat(withMediator { GetCatalogMirrorDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.isSet).isFalse()
        assertThat(settings(own).catalogs.single { it.catalog == catalog }.policy).`as`("a catalog's own policy stays").isEqualTo(CatalogMirrorPolicy.NEVER)
    }

    @Test
    fun `only an installation administrator sets the default`() {
        val tenant = createTenant("Mirror Default Denied").id
        val tenantAdmin = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-00000a1e0093"),
            externalId = "mirror-default-tenant-admin",
            email = "mirror-default-tenant-admin@epistola.test",
            displayName = "Tenant Admin",
            tenantMemberships = mapOf(tenant to setOf(TenantRole.TENANT_ADMINISTRATOR)),
            currentTenantId = tenant,
        )

        assertThatThrownBy { runAs(tenantAdmin) { SetCatalogMirrorDefault(CatalogMirrorDefaultChoice.ALL, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() } }
            .isInstanceOf(PlatformAccessDeniedException::class.java)
        assertThat(runAs(tenantAdmin) { GetCatalogMirrorDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.choice).isEqualTo(CatalogMirrorDefaultChoice.OFF)
    }
}
