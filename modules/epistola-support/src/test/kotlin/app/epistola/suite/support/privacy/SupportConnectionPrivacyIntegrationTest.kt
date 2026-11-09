// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.privacy

import app.epistola.suite.mediator.execute
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.SupportConnections
import app.epistola.suite.support.connection.TokenSupportConnection
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Every support connection has its own privacy (ADR 0053 §1): Epistola support's are the
 * installation's, another connection's what an administrator saved for it, else its configuration,
 * else the installation's.
 */
@Isolated("The installation's privacy settings apply to the whole installation")
class SupportConnectionPrivacyIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var privacy: SupportPrivacy

    @Autowired
    private lateinit var references: TenantReferences

    @Autowired
    private lateinit var registry: SupportConnectionRegistry

    @Autowired
    private lateinit var jdbi: Jdbi

    private fun connection(endpoint: String): TokenSupportConnection = registry.tokenConnections().single { it.endpoint.toString() == endpoint }

    @AfterEach
    fun forget() {
        // Saved privacy is no command's to forget: each test starts from the configuration.
        jdbi.useHandle<Exception> {
            it.createUpdate("UPDATE support_connections SET privacy = NULL WHERE endpoint IN (:a, :b)").bind("a", HOSTING).bind("b", ADVISING).execute()
        }
        withMediator { SetSupportPrivacy(TenantIdentity.CONFIDENTIAL, TelemetryDetail.PSEUDONYMISED).execute() }
        privacy.refresh()
    }

    @Test
    fun `a connection with nothing set follows the installation, also when the installation changes`() {
        val advising = connection(ADVISING).id
        withMediator { SetSupportPrivacy(TenantIdentity.CONFIDENTIAL, TelemetryDetail.PSEUDONYMISED).execute() }

        assertThat(privacy.forConnection(advising).followsInstallation).isTrue()
        assertThat(references.onWire("acme", advising)).isEqualTo(references.reference("acme"))

        withMediator { SetSupportPrivacy(TenantIdentity.NAMED, TelemetryDetail.FULL).execute() }

        assertThat(privacy.forConnection(advising).tenantIdentity).isEqualTo(TenantIdentity.NAMED)
        assertThat(references.onWire("acme", advising)).isEqualTo("acme")
    }

    @Test
    fun `a connection's configuration sets it, and what an administrator saves wins, without touching Epistola support's`() {
        val hosting = connection(HOSTING).id
        privacy.refresh()
        assertThat(privacy.forConnection(hosting).fromConfiguration).isTrue()
        assertThat(references.onWire("acme", hosting)).`as`("named in its configuration").isEqualTo("acme")
        assertThat(references.onWire("acme", SupportConnections.DISCOVERED_ID)).`as`("Epistola support stays confidential").isEqualTo(references.reference("acme"))

        val saved = withMediator { SetSupportConnectionPrivacy(hosting, TenantIdentity.CONFIDENTIAL, TelemetryDetail.FULL).execute() }

        assertThat(saved.fromConfiguration).isFalse()
        assertThat(saved.setBy).isNotNull()
        assertThat(references.onWire("acme", hosting)).isEqualTo(references.reference("acme"))
        assertThat(privacy.forConnection(hosting).telemetry).isEqualTo(TelemetryDetail.FULL)
        assertThat(privacy.current().telemetry).`as`("the installation's are unchanged").isEqualTo(TelemetryDetail.PSEUDONYMISED)
    }

    @Test
    fun `Epistola support's privacy is the installation's, set where it always was`() {
        assertThatThrownBy { withMediator { SetSupportConnectionPrivacy(SupportConnections.DISCOVERED_ID, TenantIdentity.NAMED, TelemetryDetail.FULL).execute() } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a connection that does not exist is refused`() {
        assertThatThrownBy { withMediator { SetSupportConnectionPrivacy(java.util.UUID.randomUUID(), TenantIdentity.NAMED, TelemetryDetail.FULL).execute() } }
            .isInstanceOf(SupportConnectionNotFoundException::class.java)
    }

    companion object {
        private const val HOSTING = "https://hosting.partner.example"
        private const val ADVISING = "https://advising.partner.example"

        @JvmStatic
        @DynamicPropertySource
        fun connections(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.connections[0].name") { "Hosting partner" }
            registry.add("epistola.support.connections[0].endpoint") { HOSTING }
            registry.add("epistola.support.connections[0].token") { "hosting-token" }
            registry.add("epistola.support.connections[0].tenant-identity") { "named" }
            registry.add("epistola.support.connections[1].name") { "Advising partner" }
            registry.add("epistola.support.connections[1].endpoint") { ADVISING }
            registry.add("epistola.support.connections[1].token") { "advising-token" }
        }
    }
}
