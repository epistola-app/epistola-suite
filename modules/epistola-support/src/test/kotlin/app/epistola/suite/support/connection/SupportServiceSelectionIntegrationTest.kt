// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.testing.FakeSupportResponse
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Which support services the installation uses (ADR 0050, `docs/support-protocol.md`): selected
 * here **and** granted by Epistola, asked for in every token, and safe while the service catalogue
 * changes — a service this service does not offer, a retired one, a grant and a withdrawal at runtime.
 *
 * The configuration selects `requests`, `mirror` and `translation` — a name this version does not
 * know — until a test saves a selection.
 */
class SupportServiceSelectionIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @Autowired
    private lateinit var store: SupportConnectionStore

    @BeforeEach
    fun connectedAndGrantedEverything() {
        store.forgetServices()
        store.forget()
        connection.invalidate()
        support.tokenResponse = null
        support.scopesSupported = listOf("telemetry", "requests", "mirror")
        support.olderScopeHandling = false
        connection.checkNow()
        support.activate(scopes = setOf("telemetry", "requests", "mirror"))
        connection.checkNow()
    }

    @Test
    fun `the configured selection applies, a granted service is used only when selected, and unknown names are kept`() {
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isTrue()
        assertThat(connection.hasScope(SupportScope.MIRROR)).isTrue()
        assertThat(connection.hasScope(SupportScope.TELEMETRY)).`as`("granted, not selected").isFalse()

        val selection = connection.selectedServices()
        assertThat(selection.fromConfiguration).isTrue()
        assertThat(selection.services).containsExactly("mirror", "requests", "translation")

        val overview = withMediator { GetSupportConnectionOverview().query() }
        val telemetry = overview.services.single { it.name == "telemetry" }
        assertThat(telemetry.selected).isFalse()
        assertThat(telemetry.granted).isTrue()
        assertThat(telemetry.inUse).isFalse()
        assertThat(overview.services.single { it.name == "requests" }.inUse).isTrue()
        val unknown = overview.services.single { it.name == "translation" }
        assertThat(unknown.known).isFalse()
        assertThat(unknown.inUse).isFalse()
    }

    @Test
    fun `a token asks for exactly the selected services the support service offers`() {
        // A name this suite does not know is never asked for; telemetry is granted but not selected.
        assertThat(support.tokenScopeRequests.last()).isEqualTo("mirror requests")
        val token = checkNotNull(connection.cachedAccessToken())
        assertThat(support.issuedTokens.getValue(token).second).containsExactlyInAnyOrder("requests", "mirror")
    }

    @Test
    fun `saving a selection needs no network, wins over the configuration, and stops a deselected service at once`() {
        val requestsBefore = support.requests.size

        val saved = withMediator { SaveSupportServices(setOf("telemetry", "translation")).execute() }

        assertThat(support.requests).`as`("saving talks to nobody").hasSize(requestsBefore)
        assertThat(saved.fromConfiguration).isFalse()
        assertThat(saved.services).containsExactly("telemetry", "translation")
        assertThat(connection.hasScope(SupportScope.REQUESTS)).`as`("deselected: stopped before any token expired").isFalse()
        assertThat(connection.hasScope(SupportScope.MIRROR)).isFalse()
        assertThat(connection.hasScope(SupportScope.TELEMETRY)).`as`("selected and already granted").isTrue()

        // The next check asks for the new selection.
        connection.checkNow()
        assertThat(support.tokenScopeRequests.last()).isEqualTo("telemetry")
        assertThat(support.issuedTokens.getValue(checkNotNull(connection.cachedAccessToken())).second).containsExactly("telemetry")
    }

    @Test
    fun `selecting a service drops this node's token, so nothing is sent with one that does not carry it`() {
        val stale = checkNotNull(connection.cachedAccessToken())
        assertThat(support.issuedTokens.getValue(stale).second).doesNotContain("telemetry")

        withMediator { SaveSupportServices(setOf("requests", "mirror", "telemetry")).execute() }

        assertThat(connection.hasScope(SupportScope.TELEMETRY)).`as`("selected and already granted").isTrue()
        assertThat(connection.cachedAccessToken()).`as`("telemetry would be refused with this token's 403").isNull()
        connection.refreshTokenIfDue()
        assertThat(support.issuedTokens.getValue(checkNotNull(connection.cachedAccessToken())).second).contains("telemetry")
    }

    @Test
    fun `a malformed service name is refused, and the selection stays as it was`() {
        assertThatThrownBy { withMediator { SaveSupportServices(setOf("requests", "Feedback Service")).execute() } }
            .isInstanceOf(ValidationException::class.java)

        assertThat(connection.selectedServices().fromConfiguration).isTrue()
    }

    @Test
    fun `an empty selection asks for no scope, and the token carries none`() {
        withMediator { SaveSupportServices(emptySet()).execute() }

        connection.checkNow()

        assertThat(support.tokenScopeRequests.last()).isEqualTo("")
        assertThat(support.issuedTokens.getValue(checkNotNull(connection.cachedAccessToken())).second).isEmpty()
        assertThat(SupportScope.entries.none(connection::hasScope)).isTrue()
        assertThat(connection.status().state).`as`("still activated; it simply uses nothing").isInstanceOf(SupportConnectionState.Active::class.java)
    }

    @Test
    fun `an older support service that refuses unknown scope names still issues a token, as only offered services are asked for`() {
        // This service does not offer the mirror (yet), and answers 400 invalid_scope to a name it does not know.
        support.scopesSupported = listOf("telemetry", "requests")
        support.olderScopeHandling = true
        store.forget()
        connection.invalidate()
        connection.checkNow()
        support.activate(scopes = setOf("telemetry", "requests"))
        connection.checkNow()

        assertThat(support.tokenScopeRequests.last()).isEqualTo("requests")
        assertThat(connection.status().lastError).isNull()
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isTrue()
        assertThat(connection.hasScope(SupportScope.MIRROR)).isFalse()
    }

    @Test
    fun `a selected service the support service no longer offers is shown as retired, and the selection keeps it`() {
        support.scopesSupported = listOf("telemetry", "requests")
        // The cached metadata is refreshed by the check after its hour; a new registration reads it at once.
        store.forget()
        connection.invalidate()
        connection.checkNow()

        val overview = withMediator { GetSupportConnectionOverview().query() }
        val mirror = overview.services.single { it.name == "mirror" }
        assertThat(mirror.selected).isTrue()
        assertThat(mirror.offered).isFalse()
        assertThat(mirror.noLongerOffered).isTrue()
        assertThat(overview.services.single { it.name == "requests" }.noLongerOffered).isFalse()
        assertThat(connection.selectedServices().services).contains("mirror")
        assertThat(support.tokenScopeRequests.last()).isEqualTo("requests")
    }

    @Test
    fun `a granted service the support service stops offering is not used, and is used again once offered`() {
        // Retiring starts by dropping the scope from scopes_supported while the registration keeps
        // its grant: tokens stop carrying it, so every call would only be refused.
        support.scopesSupported = listOf("telemetry", "requests")
        connection.checkNow()

        assertThat((connection.status().state as SupportConnectionState.Active).scopes).`as`("still granted at the service").contains(SupportScope.MIRROR)
        assertThat(connection.hasScope(SupportScope.MIRROR)).`as`("no longer offered: not used").isFalse()
        assertThat(connection.hasScope(SupportScope.REQUESTS)).isTrue()
        val mirror = withMediator { GetSupportConnectionOverview().query() }.services.single { it.name == "mirror" }
        assertThat(mirror.noLongerOffered).isTrue()
        assertThat(mirror.inUse).isFalse()

        support.scopesSupported = listOf("telemetry", "requests", "mirror")
        connection.checkNow()

        assertThat(connection.hasScope(SupportScope.MIRROR)).`as`("offered again").isTrue()
        assertThat(support.tokenScopeRequests.last()).isEqualTo("mirror requests")
    }

    @Test
    fun `a tenant's support overview does not show a retired service as available`() {
        support.scopesSupported = listOf("telemetry", "requests")
        connection.checkNow()
        assertThat(connection.hasScope(SupportScope.MIRROR)).isFalse()

        val tenant = createTenant("Support Retired Service")
        val services = withMediator { GetTenantSupportSummary(tenant.id).query() }.services

        assertThat(services.single { it.scope == SupportScope.MIRROR }.inUse).`as`("what hasScope says").isFalse()
        assertThat(services.single { it.scope == SupportScope.REQUESTS }.inUse).isTrue()
    }

    @Autowired
    private lateinit var properties: app.epistola.suite.support.SupportProperties

    @Autowired
    private lateinit var serviceClient: SupportServiceClient

    @Autowired
    private lateinit var supportConnections: SupportConnections

    @Autowired
    private lateinit var installations: app.epistola.suite.installation.InstallationService

    @Autowired
    private lateinit var installation: app.epistola.suite.installation.InstallationProperties

    @Autowired
    private lateinit var nodeIdentity: app.epistola.suite.observability.NodeIdentity

    @Autowired
    private lateinit var buildProperties: ObjectProvider<BuildProperties>

    @Autowired
    private lateinit var supportHttp: SupportHttp

    @Autowired
    private lateinit var cipher: app.epistola.suite.crypto.CredentialCipher

    @Autowired
    private lateinit var objectMapper: tools.jackson.databind.ObjectMapper

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    @Test
    fun `a selection saved in a transaction is not undone on this node by a read racing the commit`() {
        withMediator { SaveSupportServices(setOf("requests", "mirror")).execute() }
        // A second connection over the same rows, whose store lets the test hold a read: the race
        // is a telemetry export or a gate check reading while the command's transaction is open.
        var onRead: (() -> Unit)? = null
        val racing = anotherNode(
            object : SupportConnectionStore(jdbi, cipher, objectMapper, withMediator { EnsureConfiguredSupportConnection().execute() }) {
                override fun services(): StoredSupportServices? = super.services().also { onRead?.invoke() }
            },
        )
        assertThat(racing.isSelected(SupportScope.MIRROR)).isTrue()

        val readerHasRead = CountDownLatch(1)
        val readerMayStore = CountDownLatch(1)
        val reader = Thread { racing.isSelected(SupportScope.MIRROR) }
        var saving = true
        onRead = {
            if (Thread.currentThread() === reader) {
                // The reader read the committed (old) row; it stores it only once the save is done.
                readerHasRead.countDown()
                readerMayStore.await(5, TimeUnit.SECONDS)
            } else if (saving) {
                // The saving node re-reads after invalidating: the reader reads in between.
                saving = false
                reader.start()
                readerHasRead.await(5, TimeUnit.SECONDS)
            }
        }
        TransactionTemplate(transactions).executeWithoutResult { racing.saveSelectedServices(listOf("requests"), "test") }
        readerMayStore.countDown()
        reader.join(5_000)
        onRead = null

        assertThat(racing.isSelected(SupportScope.MIRROR)).`as`("deselected: stops at once on this node").isFalse()
    }

    @Test
    fun `a grant reaches a selected service at the next check`() {
        support.activate(scopes = setOf("requests"))
        connection.checkNow()
        assertThat(connection.hasScope(SupportScope.MIRROR)).isFalse()

        support.activate(scopes = setOf("requests", "mirror"))
        connection.checkNow()

        assertThat(connection.hasScope(SupportScope.MIRROR)).isTrue()
        assertThat(support.issuedTokens.getValue(checkNotNull(connection.cachedAccessToken())).second).contains("mirror")
    }

    @Test
    fun `a scope the service refuses is not used on this node until a check finds it granted again`() {
        assertThat(connection.hasScope(SupportScope.MIRROR)).isTrue()
        // Staff withdraw the mirror: the service refuses it at once, before the token expires.
        support.activate(scopes = setOf("requests"))

        connection.scopeRefused(SupportScope.MIRROR)

        assertThat(connection.hasScope(SupportScope.MIRROR)).`as`("stopped at once").isFalse()
        assertThat(connection.hasScope(SupportScope.REQUESTS)).`as`("the other service carries on").isTrue()

        // The token refresh replaces the token that still names it, without asking twice.
        val tokensBefore = support.tokensIssued
        connection.refreshTokenIfDue()
        connection.refreshTokenIfDue()
        assertThat(support.tokensIssued).isEqualTo(tokensBefore + 1)
        assertThat(connection.hasScope(SupportScope.MIRROR)).isFalse()

        connection.checkNow()
        assertThat(connection.hasScope(SupportScope.MIRROR)).`as`("the check agrees: withdrawn").isFalse()

        support.activate(scopes = setOf("requests", "mirror"))
        connection.checkNow()
        assertThat(connection.hasScope(SupportScope.MIRROR)).`as`("granted again").isTrue()
    }

    @Test
    fun `a refusal by a token from before the scope was asked for only replaces that token`() {
        withMediator { SaveSupportServices(setOf("requests")).execute() }
        connection.checkNow()
        // Another node holds a token from before the mirror was selected; this node saves the selection.
        val otherNode = anotherNode()
        val stale = otherNode.accessToken()
        withMediator { SaveSupportServices(setOf("requests", "mirror")).execute() }
        otherNode.invalidate() // it reads the stored rows again within 30 seconds
        assertThat(support.issuedTokens.getValue(stale).second).doesNotContain("mirror")

        otherNode.scopeRefused(SupportScope.MIRROR)

        assertThat(otherNode.hasScope(SupportScope.MIRROR)).`as`("granted and selected: not a withdrawal").isTrue()
        assertThat(otherNode.accessToken()).isNotEqualTo(stale)
        assertThat(support.tokenScopeRequests.last()).isEqualTo("mirror requests")
    }

    @Test
    fun `a selection saved on one node applies on every other node within a second`() {
        val otherNode = anotherNode()
        val stale = otherNode.accessToken()
        assertThat(otherNode.hasScope(SupportScope.REQUESTS)).isTrue()
        assertThat(otherNode.hasScope(SupportScope.TELEMETRY)).`as`("granted, not selected").isFalse()

        withMediator { SaveSupportServices(setOf("telemetry")).execute() }
        testClock.advanceBy(Duration.ofSeconds(1))

        assertThat(otherNode.hasScope(SupportScope.REQUESTS)).`as`("deselected on another node: stopped there too").isFalse()
        assertThat(otherNode.hasScope(SupportScope.TELEMETRY)).`as`("selected on another node: used there too").isTrue()
        // Its token asked for the old selection: nothing is sent with one that lacks telemetry.
        assertThat(otherNode.cachedAccessToken()).`as`("the token from before the selection is dropped").isNull()
        assertThat(otherNode.accessToken()).isNotEqualTo(stale)
        assertThat(support.tokenScopeRequests.last()).isEqualTo("telemetry")
    }

    @Test
    fun `a scope refused on one node stops it on every node at once, until a newer token grants it again`() {
        val otherNode = anotherNode()
        otherNode.accessToken()
        assertThat(otherNode.hasScope(SupportScope.MIRROR)).isTrue()

        // Staff withdraw the mirror; this node's next mirror call is refused.
        support.activate(scopes = setOf("requests"))
        connection.scopeRefused(SupportScope.MIRROR)
        testClock.advanceBy(Duration.ofSeconds(1))

        assertThat(otherNode.hasScope(SupportScope.MIRROR)).`as`("refused on another node: stopped here too, without a refused request of its own").isFalse()
        assertThat(otherNode.hasScope(SupportScope.REQUESTS)).isTrue()

        // Granted again: the other node's next token carries it, which settles the refusal for every node.
        support.activate(scopes = setOf("requests", "mirror"))
        testClock.advanceBy(Duration.ofSeconds(support.tokenLifetimeSeconds))
        otherNode.refreshTokenIfDue()
        assertThat(otherNode.hasScope(SupportScope.MIRROR)).`as`("a newer token carries it").isTrue()
        testClock.advanceBy(Duration.ofSeconds(1))
        assertThat(connection.hasScope(SupportScope.MIRROR)).`as`("the refusal is cleared for every node").isTrue()
    }

    @Test
    fun `a token asked for before another node's refusal does not settle it, even at the same instant`() {
        val otherNode = anotherNode()
        otherNode.accessToken()
        testClock.advanceBy(Duration.ofSeconds(support.tokenLifetimeSeconds))
        // While the other node's token request is under way, this node is refused the mirror. The
        // service answered the token request before the withdrawal, so the token still carries it;
        // the test clock stands still, so every instant here ties.
        support.tokenResponse = {
            support.tokenResponse = null
            connection.scopeRefused(SupportScope.MIRROR)
            null
        }

        otherNode.refreshTokenIfDue()

        assertThat(otherNode.hasScope(SupportScope.MIRROR)).`as`("its token was asked for before the refusal: the refusal stands").isFalse()
        testClock.advanceBy(Duration.ofSeconds(1))
        assertThat(connection.hasScope(SupportScope.MIRROR)).`as`("and stays shared").isFalse()
    }

    @Test
    fun `a node whose token predates a grant another node learned asks for a new token instead of sending with it`() {
        val otherNode = anotherNode()
        otherNode.accessToken()
        // Staff withdraw the mirror; the other node's next token no longer carries it.
        support.activate(scopes = setOf("requests"))
        testClock.advanceBy(Duration.ofSeconds(support.tokenLifetimeSeconds))
        otherNode.refreshTokenIfDue()
        val withoutMirror = checkNotNull(otherNode.cachedAccessToken())
        assertThat(support.issuedTokens.getValue(withoutMirror).second).doesNotContain("mirror")
        assertThat(otherNode.hasScope(SupportScope.MIRROR)).isFalse()

        // Granted again, and this node's check learns it: every node reads the mirror as granted.
        testClock.advanceBy(Duration.ofMinutes(1))
        support.activate(scopes = setOf("requests", "mirror"))
        connection.checkNow()
        testClock.advanceBy(Duration.ofSeconds(1))
        assertThat(otherNode.hasScope(SupportScope.MIRROR)).isTrue()

        // The service takes longer to answer than a caller waits for another thread's token request (250 ms).
        support.tokenResponse = {
            Thread.sleep(600)
            null
        }
        assertThat(otherNode.cachedAccessToken()).`as`("not the token that lacks the mirror").isNotEqualTo(withoutMirror)
        // Reading it started a renewal in the background, which holds the token lock until it is done.
        otherNode.awaitTokenRenewal()
        assertThat(support.issuedTokens.getValue(otherNode.accessToken()).second).`as`("a new one carries it").contains("mirror")
    }

    /**
     * The check runs on a node whose clock is behind the other node's (within the service's skew), so
     * by their instants the other node's token, asked for before the check, looks newer than it.
     */
    @Test
    fun `a grant a check learned on a node whose clock is behind reaches a node with an older token at once`() {
        support.activate(scopes = setOf("requests"))
        connection.checkNow()
        val otherNode = anotherNode()
        val older = otherNode.accessToken()
        assertThat(support.issuedTokens.getValue(older).second).doesNotContain("mirror")
        val otherNodeTime = EpistolaClock.instant()

        support.activate(scopes = setOf("requests", "mirror"))
        testClock.set(otherNodeTime.minus(Duration.ofMinutes(2)))
        connection.checkNow()
        testClock.set(otherNodeTime.plus(Duration.ofSeconds(2)))

        assertThat(otherNode.hasScope(SupportScope.MIRROR)).`as`("the check is newer than that token").isTrue()
        assertThat(otherNode.cachedAccessToken()).`as`("and that token is not sent with").isNotEqualTo(older)
        assertThat(support.issuedTokens.getValue(otherNode.accessToken()).second).contains("mirror")
    }

    @Test
    fun `a token fetch under way when another node's selection is read is not used for the new selection`() {
        val otherNode = anotherNode()
        assertThat(otherNode.hasScope(SupportScope.REQUESTS)).isTrue()
        // While the other node waits for its token, this node saves a selection and the other node reads it.
        support.tokenResponse = {
            support.tokenResponse = null
            connection.saveSelectedServices(listOf("requests", "mirror", "telemetry"), "test")
            otherNode.invalidate()
            assertThat(otherNode.hasScope(SupportScope.TELEMETRY)).isTrue()
            null
        }

        val inFlight = otherNode.accessToken()

        assertThat(support.issuedTokens.getValue(inFlight).second).doesNotContain("telemetry")
        assertThat(otherNode.cachedAccessToken()).`as`("the token asked for the old selection").isNotEqualTo(inFlight)
        assertThat(support.issuedTokens.getValue(otherNode.accessToken()).second).contains("telemetry")
    }

    @Test
    fun `a node that cannot read the stored refusals does not use a refused scope`() {
        val flaky = FlakyRefusalsStore()
        val otherNode = anotherNode(flaky)
        otherNode.accessToken()
        assertThat(otherNode.hasScope(SupportScope.MIRROR)).isTrue()
        support.activate(scopes = setOf("requests"))
        connection.scopeRefused(SupportScope.MIRROR)

        flaky.failRefusals = true
        otherNode.invalidate()

        assertThat(otherNode.hasScope(SupportScope.MIRROR)).`as`("never more than was granted").isFalse()
        flaky.failRefusals = false
        testClock.advanceBy(Duration.ofSeconds(1))
        assertThat(otherNode.hasScope(SupportScope.REQUESTS)).`as`("readable again: the rest carries on").isTrue()
        assertThat(otherNode.hasScope(SupportScope.MIRROR)).isFalse()
    }

    /** A store whose refusals cannot be read while [failRefusals] is set, as when its pool times out. */
    private inner class FlakyRefusalsStore : SupportConnectionStore(jdbi, cipher, objectMapper, withMediator { EnsureConfiguredSupportConnection().execute() }) {
        @Volatile var failRefusals = false

        override fun refusals(): StoredSupportRefusals = if (failRefusals) throw IllegalStateException("simulated: no connection") else super.refusals()
    }

    /** A second connection over the same rows: another node of the installation. */
    private fun anotherNode(store: SupportConnectionStore = supportConnections.configuredStore()) = DefaultSupportConnection(
        properties,
        store,
        serviceClient,
        supportConnections,
        installations,
        installation,
        nodeIdentity,
        buildProperties.ifAvailable,
        supportHttp,
    )

    @Test
    fun `an unreachable support service does not stop an administrator from deselecting`() {
        support.tokenResponse = { FakeSupportResponse.json(503, """{"error":"temporarily_unavailable"}""") }
        testClock.advanceBy(Duration.ofSeconds(support.tokenLifetimeSeconds))
        connection.checkNow()
        assertThat(connection.status().lastError).contains("503")

        withMediator { SaveSupportServices(setOf("mirror")).execute() }

        assertThat(connection.hasScope(SupportScope.REQUESTS)).isFalse()
        assertThat(connection.selectedServices().services).containsExactly("mirror")
    }

    companion object {
        private val support = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.privacy.tenant-identity") { "named" }
            registry.add("epistola.support.auto-register") { "true" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.services") { "Requests, mirror,translation" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }
}
