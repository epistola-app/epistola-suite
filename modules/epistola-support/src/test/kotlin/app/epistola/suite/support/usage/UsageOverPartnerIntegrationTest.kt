// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.usage

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.mediator.execute
import app.epistola.suite.statistics.GenerationStatisticsRollup
import app.epistola.suite.statistics.StatisticsProperties
import app.epistola.suite.support.connection.AddSupportConnection
import app.epistola.suite.support.connection.DisconnectSupportConnection
import app.epistola.suite.support.connection.RegisterSupportConnection
import app.epistola.suite.support.connection.RemoveSupportConnection
import app.epistola.suite.support.connection.SupportConnectionAuth
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.TokenSupportConnection
import app.epistola.suite.support.privacy.TenantReferences
import app.epistola.suite.support.sharing.SetSharingRestriction
import app.epistola.suite.support.sharing.SharingRestriction
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.default
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.time.EpistolaClock
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.time.temporal.ChronoUnit

/**
 * Usage reported over a partner connection (ADR 0053 step 3), signed in with a token the partner
 * issued, with no connection to Epistola support: the partner gets each finished hour with the tenants
 * that share usage with it, named as its privacy says.
 */
@Isolated("Usage restrictions and reported hours apply to the whole installation")
class UsageOverPartnerIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var reporter: UsageReporter

    @Autowired
    private lateinit var registry: SupportConnectionRegistry

    @Autowired
    private lateinit var references: TenantReferences

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbi: Jdbi

    private val statistics by lazy { GenerationStatisticsRollup(jdbi, StatisticsProperties(settle = java.time.Duration.ZERO)) }

    private fun connection(): TokenSupportConnection = registry.tokenConnections().single { it.endpoint.toString() == partner.baseUrl.trimEnd('/') }

    @BeforeEach
    fun connect() {
        partner.issuePartnerToken(TOKEN, setOf("usage"))
        connection().checkNow()
        partner.usageReports.clear()
        // Which hours went out is bookkeeping no command writes: each test starts with none, from this hour on.
        jdbi.useHandle<Exception> {
            it.createUpdate("DELETE FROM support_usage_reports").execute()
            it.createUpdate("DELETE FROM app_metadata WHERE key LIKE :key").bind("key", "${UsageReporter.SINCE_KEY}%").execute()
        }
    }

    @AfterEach
    fun reset() {
        withMediator { SetSharingRestriction(USAGE_SECTION, null, connection().id).execute() }
    }

    private fun tenantWithUse(name: String): TenantKey = scenario {
        given {
            val tenant = tenant(name)
            val template = template(tenant.id, "Letter")
            val templateId = TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))
            val variant = variant(templateId, "Default")
            released(VariantId(variant.id, templateId), TestTemplateBuilder.buildMinimal(name = "Letter"))
            Triple(tenant.id, template.id, variant.id)
        }.whenever { (tenant, template, variant) ->
            val data = objectMapper.createObjectNode().put("name", "Jan")
            execute(GenerateDocument(catalog = testCatalog(tenant, CatalogKey.DEFAULT), templateId = template, variantId = variant, environmentId = null, data = data, filename = "a.pdf"))
            drainGenerationJobs(tenant)
            tenant
        }.result
    }

    @Test
    fun `the partner gets the finished hour, each tenant named as the partner's privacy says, and Epistola support nothing`() {
        val tenant = tenantWithUse("Usage Over Partner")
        withMediator { SetSharingRestriction(USAGE_SECTION, SharingRestriction.REQUIRED, connection().id).execute() }
        statistics.rollUp()
        val hour = EpistolaClock.instant().truncatedTo(ChronoUnit.HOURS)

        val round = EpistolaClock.withInstant(hour.plus(1, ChronoUnit.HOURS).plusSeconds(30)) { reporter.report(connection().id) }

        assertThat(round.sent).`as`("round: %s", round).contains(hour)
        val body = partner.usageReports.getValue(hour.toString())
        val tenants: List<String> = objectMapper.readTree(body).path("tenants").toList().map { it.path("tenant").asString() }
        assertThat(tenants).`as`("report %s", body).contains(references.reference(tenant)).doesNotContain(tenant.value)
        assertThat(partner.bearerTokensSeen.filter { it.first.startsWith("/api/v1/usage/hours/") }.map { it.second }.distinct()).containsExactly(TOKEN)
    }

    @Test
    fun `while usage is not allowed for the partner nothing goes there`() {
        tenantWithUse("Usage Not To Partner")
        withMediator { SetSharingRestriction(USAGE_SECTION, SharingRestriction.NOT_ALLOWED, connection().id).execute() }
        statistics.rollUp()
        val hour = EpistolaClock.instant().truncatedTo(ChronoUnit.HOURS)

        val round = EpistolaClock.withInstant(hour.plus(1, ChronoUnit.HOURS).plusSeconds(30)) { reporter.report(connection().id) }

        assertThat(round.sent).isEmpty()
        assertThat(partner.usageReports).isEmpty()
    }

    @Test
    fun `a partner that registers through OAuth gets usage too, once it grants and is selected for it`() {
        val tenant = tenantWithUse("Usage Over Registering Partner")
        val id = withMediator {
            AddSupportConnection("Registering partner", registeringPartner.baseUrl, token = "", services = listOf("usage"), auth = SupportConnectionAuth.PRIVATE_KEY_JWT).execute()
        }
        try {
            withMediator { RegisterSupportConnection(connectionId = id).execute() }
            val pending = registry.registering(id)!!.status().state as SupportConnectionState.Pending
            registeringPartner.activate(pending.clientId, setOf("usage"))
            registry.registering(id)!!.checkNow()
            withMediator { SetSharingRestriction(USAGE_SECTION, SharingRestriction.REQUIRED, id).execute() }
            statistics.rollUp()
            val hour = EpistolaClock.instant().truncatedTo(ChronoUnit.HOURS)

            val round = EpistolaClock.withInstant(hour.plus(1, ChronoUnit.HOURS).plusSeconds(30)) { reporter.report(id) }

            assertThat(round.sent).`as`("round: %s", round).contains(hour)
            val body = registeringPartner.usageReports.getValue(hour.toString())
            assertThat(objectMapper.readTree(body).path("tenants").toList().map { it.path("tenant").asString() }).contains(references.reference(tenant))
        } finally {
            withMediator { DisconnectSupportConnection(connectionId = id).execute() }
            withMediator { RemoveSupportConnection(id).execute() }
        }
    }

    companion object {
        private const val TOKEN = "partner-usage-token"

        private val partner = FakeSupportServer()
        private val registeringPartner = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun partnerConnection(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.connections[0].name") { "Acme Partner" }
            registry.add("epistola.support.connections[0].endpoint") { partner.baseUrl }
            registry.add("epistola.support.connections[0].token") { TOKEN }
            registry.add("epistola.support.connections[0].services") { "usage" }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            partner.close()
            registeringPartner.close()
        }
    }
}
