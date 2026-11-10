// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.support.connection.TokenSupportConnection
import app.epistola.suite.support.mirror.CatalogMirrorReconcilerIntegrationTest.FakeMirror
import app.epistola.suite.support.privacy.SetSupportConnectionPrivacy
import app.epistola.suite.support.privacy.TelemetryDetail
import app.epistola.suite.support.privacy.TenantIdentity
import app.epistola.suite.testing.FakeSupportFault
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * The catalog mirror as a service of every connection that grants it (ADR 0053 step 3i): a partner
 * signed in with a token gets the tenants that share with it, each connection with its own choices,
 * policies, state and failures, beside Epistola support's.
 *
 * The database is shared with other test classes; every assertion is about this test's own tenants.
 */
class CatalogMirrorPerConnectionIntegrationTest : CatalogMirrorTestBase() {
    @Autowired
    private lateinit var epistola: DefaultSupportConnection

    @Autowired
    private lateinit var supportStore: SupportConnectionStore

    @Autowired
    private lateinit var registry: SupportConnectionRegistry

    @Autowired
    private lateinit var reconciler: CatalogMirrorReconciler

    private fun partner(): TokenSupportConnection = registry.tokenConnections().single { it.endpoint.toString() == partnerService.baseUrl.trimEnd('/') }

    @BeforeEach
    fun connect() {
        epistolaMirror.reset()
        partnerMirror.reset()
        partnerService.clearFaults()
        supportStore.forget()
        epistola.invalidate()
        epistola.checkNow()
        support.activate(scopes = setOf("mirror"))
        support.expireTokens()
        epistola.checkNow()
        assertThat(epistola.status().state).isInstanceOf(SupportConnectionState.Active::class.java)
        partnerService.issuePartnerToken(TOKEN, setOf("mirror"))
        partner().checkNow()
        assertThat(partner().status().state).isInstanceOf(SupportConnectionState.Active::class.java)
    }

    @Test
    fun `a tenant that shares with a partner has its releases mirrored there, and only there`() {
        val tenant = createTenant("Mirror Partner Only").id
        val letters = releasedCatalog(tenant, "letters", "1.0.0")
        withMediator {
            OptInCatalogMirror(tenant, shareByDefault = true, consentVersion = CatalogMirror.CONSENT_VERSION, connectionId = partner().id).execute()
            SetCatalogMirrorPolicy(testCatalog(tenant, SYSTEM_CATALOG_KEY), CatalogMirrorPolicy.NEVER, partner().id).execute()
        }

        val reports = reconciler.reconcileEvery()

        assertThat(reports[partner().id]!!.tenants[tenant.value]).isEqualTo(TenantReconcileResult.SENT)
        assertThat(partnerMirror.held[tenant.value]).containsExactlyElementsOf(contentHashes(letters).values)
        assertThat(partnerMirror.uploadsOf(contentHashes(letters).values)).hasSize(1)
        assertThat(epistolaMirror.held).`as`("the tenant does not share with Epistola support").doesNotContainKey(tenant.value)
    }

    @Test
    fun `a partner that cannot be reached holds back nothing that goes to Epistola support`() {
        val tenant = createTenant("Mirror Partner Down").id
        releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        withMediator {
            OptInCatalogMirror(tenant, shareByDefault = true, consentVersion = CatalogMirror.CONSENT_VERSION, connectionId = partner().id).execute()
            SetCatalogMirrorPolicy(testCatalog(tenant, SYSTEM_CATALOG_KEY), CatalogMirrorPolicy.NEVER, partner().id).execute()
        }
        partnerService.fault(FakeSupportFault.unavailable(), pathPrefix = "/api/v1/mirror")

        val reports = reconciler.reconcileEvery()

        assertThat(reports[epistola.id]!!.tenants[tenant.value]).isEqualTo(TenantReconcileResult.SENT)
        assertThat(epistolaMirror.held).containsKey(tenant.value)
        assertThat(reports[partner().id]!!.tenants[tenant.value]).isNotEqualTo(TenantReconcileResult.SENT)
        assertThat(partnerMirror.held).doesNotContainKey(tenant.value)
    }

    @Test
    fun `stopping sharing with the partner withdraws the tenant there and leaves Epistola support's copy`() {
        val tenant = createTenant("Mirror Partner Withdraw").id
        releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        withMediator {
            OptInCatalogMirror(tenant, shareByDefault = true, consentVersion = CatalogMirror.CONSENT_VERSION, connectionId = partner().id).execute()
            SetCatalogMirrorPolicy(testCatalog(tenant, SYSTEM_CATALOG_KEY), CatalogMirrorPolicy.NEVER, partner().id).execute()
        }
        reconciler.reconcileEvery()
        assertThat(partnerMirror.held).containsKey(tenant.value)

        withMediator { OptOutCatalogMirror(tenant, partner().id).execute() }
        val reports = reconciler.reconcileEvery()

        assertThat(reports[partner().id]!!.tenants[tenant.value]).isEqualTo(TenantReconcileResult.WITHDRAWN)
        assertThat(partnerMirror.held).doesNotContainKey(tenant.value)
        assertThat(epistolaMirror.held).`as`("still shared with Epistola support").containsKey(tenant.value)
        assertThat(settings(tenant).sharing).isTrue()
        assertThat(withMediator { GetCatalogMirrorSettings(tenant, connectionId = partner().id).query() }.sharing).isFalse()
    }

    @Test
    fun `tenants becoming confidential to the partner forget their choices with it only`() {
        val tenant = createTenant("Mirror Partner Confidential").id
        releasedCatalog(tenant, "letters", "1.0.0")
        optInOwnCatalogs(tenant)
        withMediator { OptInCatalogMirror(tenant, shareByDefault = true, consentVersion = CatalogMirror.CONSENT_VERSION, connectionId = partner().id).execute() }

        withMediator { SetSupportConnectionPrivacy(partner().id, TenantIdentity.CONFIDENTIAL, TelemetryDetail.PSEUDONYMISED).execute() }
        try {
            assertThat(withMediator { GetCatalogMirrorSettings(tenant, connectionId = partner().id).query() }.consent).`as`("forgotten for the partner").isNull()
            assertThat(settings(tenant).sharing).`as`("still shared with Epistola support").isTrue()
        } finally {
            withMediator { SetSupportConnectionPrivacy(partner().id, TenantIdentity.NAMED, TelemetryDetail.PSEUDONYMISED).execute() }
        }
    }

    companion object {
        private const val TOKEN = "partner-mirror-token-0001"
        private val support = FakeSupportServer()
        private val epistolaMirror = FakeMirror().also { it.install(support) }
        private val partnerService = FakeSupportServer()
        private val partnerMirror = FakeMirror().also { it.install(partnerService) }

        @JvmStatic
        @DynamicPropertySource
        fun supportServices(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.services") { "mirror" }
            registry.add("epistola.support.auto-register") { "true" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.connections[0].name") { "Acme Partner" }
            registry.add("epistola.support.connections[0].endpoint") { partnerService.baseUrl }
            registry.add("epistola.support.connections[0].token") { TOKEN }
            registry.add("epistola.support.connections[0].services") { "mirror" }
            registry.add("epistola.support.connections[0].tenant-identity") { "named" }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            support.close()
            partnerService.close()
        }
    }
}
