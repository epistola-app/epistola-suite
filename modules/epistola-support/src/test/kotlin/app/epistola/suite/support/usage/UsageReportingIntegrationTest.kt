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
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.statistics.GenerationStatisticsRollup
import app.epistola.suite.statistics.PreviewStatisticsCounter
import app.epistola.suite.statistics.StatisticsProperties
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.support.connection.SupportConnectionStore
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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * Usage for billing (ADR 0052 §6), with the configuration's default of confidential tenants: documents
 * generated and previews rendered are counted per tenant and hour, and a finished hour is reported with
 * each sharing tenant's figures and running totals, under its reference.
 */
@Isolated("Usage settings and counts apply to the whole installation")
class UsageReportingIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var connection: DefaultSupportConnection

    @Autowired
    private lateinit var store: SupportConnectionStore

    @Autowired
    private lateinit var reporter: UsageReporter

    @Autowired
    private lateinit var references: TenantReferences

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbi: org.jdbi.v3.core.Jdbi

    @BeforeEach
    fun grantedUsage() {
        store.forget()
        connection.invalidate()
        connection.checkNow()
        support.activate(scopes = setOf("usage"))
        support.expireTokens()
        connection.checkNow()
        support.usageReports.clear()
        // Every test uses the same test-clock hour; which hours were reported is bookkeeping no command
        // writes, so each test starts with none reported, from the current hour on.
        jdbi.useHandle<Exception> {
            it.createUpdate("DELETE FROM support_usage_reports").execute()
            it.createUpdate("DELETE FROM app_metadata WHERE key LIKE :key").bind("key", "${UsageReporter.SINCE_KEY}.%").execute()
        }
    }

    @AfterEach
    fun reset() {
        withMediator {
            SetSharingRestriction(USAGE_SECTION, null, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
            ForgetUsageSharing(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
        }
    }

    /** A tenant with one released template that generated one document and rendered two previews today. */
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
            repeat(2) {
                PreviewDocument(catalog = testCatalog(tenant, CatalogKey.DEFAULT), templateId = template, data = data, variantId = variant, catalogVersion = null).query()
            }
            tenant
        }.result
    }

    private var lastRound: UsageReportRound? = null

    @Autowired
    private lateinit var previews: PreviewStatisticsCounter

    @Autowired
    private lateinit var metadata: app.epistola.suite.metadata.AppMetadataService

    /** Core's statistics roll-up, without its settle delay, so what a test just generated is counted. */
    private val statistics by lazy { GenerationStatisticsRollup(jdbi, StatisticsProperties(settle = java.time.Duration.ZERO)) }

    /** Reports as if it were the next hour, when the hour the use happened in is finished and counted. */
    private fun reportNextHour(): Map<String, List<Long>> {
        statistics.rollUp()
        previews.flush()
        val hour = EpistolaClock.instant().truncatedTo(ChronoUnit.HOURS)
        lastRound = EpistolaClock.withInstant(hour.plus(1, ChronoUnit.HOURS).plusSeconds(30)) { reporter.report() }
        val body = support.usageReports[hour.toString()] ?: return emptyMap()
        return objectMapper.readTree(body).path("tenants").associate { r ->
            r.path("tenant").asString() to listOf("generated", "previews", "generatedTotal", "previewsTotal").map { r.path(it).asLong() }
        }
    }

    @Autowired
    private lateinit var applicationContext: org.springframework.context.ApplicationContext

    @Test
    fun `usage is reported by one node per round, never by one whose clock is off`() {
        val definition = applicationContext.getBean(UsageReportScheduler::class.java).usageReportTaskDefinition()

        assertThat(definition.executionScope).isEqualTo(app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope.SINGLE_OWNER)
        assertThat(definition.requiredCapability).isEqualTo(app.epistola.suite.support.connection.SupportCluster.CAPABILITY)
        assertThat(UsageReporter::class.java.declaredMethods.filter { it.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled::class.java) })
            .`as`("no node-local timer beside the cluster task").isEmpty()
    }

    @Test
    fun `required, every tenant's documents and previews of a finished day are reported under its reference`() {
        val tenant = tenantWithUse("Usage Required")
        withMediator { SetSharingRestriction(USAGE_SECTION, SharingRestriction.REQUIRED, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        val reported = reportNextHour()

        assertThat(reported).`as`("round: %s, reports: %s", lastRound, support.usageReports).containsEntry(references.reference(tenant), listOf(1L, 2L, 1L, 2L))
        assertThat(reported.keys).doesNotContain(tenant.value)
    }

    @Test
    fun `shared by default, a tenant that opted out is left out, and not allowed, nothing is reported`() {
        val sharing = tenantWithUse("Usage Default")
        val optedOut = tenantWithUse("Usage Opted Out")
        withMediator {
            SetUsageDefault(share = true, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
            SetUsageChoice(optedOut, share = false, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
        }

        val reported = reportNextHour()

        assertThat(reported).containsKey(references.reference(sharing)).doesNotContainKey(references.reference(optedOut))

        support.usageReports.clear()
        withMediator { SetSharingRestriction(USAGE_SECTION, SharingRestriction.NOT_ALLOWED, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        assertThat(reportNextHour()).isEmpty()
        assertThat(support.usageReports).isEmpty()
    }

    @Test
    fun `a tenant's own usage choice reads back as declined or shared, and following the default forgets it`() {
        val tenant = createTenant("Usage Choice").id
        withMediator {
            SetUsageDefault(share = true, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()

            val declined = SetUsageChoice(tenant, share = false, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
            assertThat(declined.share).isFalse()
            assertThat(declined.decidedAt).isNotNull()

            val shared = SetUsageChoice(tenant, share = true, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
            assertThat(shared.share).isTrue()
            assertThat(shared.decidedAt).isAfterOrEqualTo(declined.decidedAt)

            assertThat(SetUsageChoice(tenant, share = false, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute().share).`as`("declined again after sharing").isFalse()

            val following = FollowUsageDefault(tenant, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute()
            assertThat(following.share).`as`("no choice of its own").isNull()
            assertThat(GetUsageChoice(tenant, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query().share).isNull()
        }
    }

    @Test
    fun `the installation reports at one moment within the first ten minutes of every hour, chosen once and kept`() {
        val moment = reporter.reportMoment()

        assertThat(moment).isBetween(java.time.Duration.ZERO, java.time.Duration.ofSeconds(599))
        assertThat(reporter.reportMoment()).isEqualTo(moment)
    }

    @Test
    fun `a moment stored outside the first ten minutes is chosen again`() {
        // A moment from before the window was narrowed.
        metadata.setAs(UsageReporter.MOMENT_KEY, mapOf("minute" to 51, "second" to 31))
        val fresh = UsageReporter(connection, applicationContext.getBeanProvider(app.epistola.suite.support.connection.SupportConnectionRegistry::class.java), jdbi, references, objectMapper, metadata, mediator, StatisticsProperties(), app.epistola.suite.support.SupportProperties())

        assertThat(fresh.reportMoment()).isLessThan(java.time.Duration.ofMinutes(10))
        assertThat(metadata.get(UsageReporter.MOMENT_KEY)!!.get("minute").asInt()).isLessThan(10)
    }

    companion object {
        private val support = FakeSupportServer()

        @JvmStatic
        @DynamicPropertySource
        fun supportService(registry: DynamicPropertyRegistry) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.auto-register") { "true" }
            registry.add("epistola.support.endpoint") { support.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
            registry.add("epistola.support.services") { "usage" }
            registry.add("epistola.support.usage.backlog") { "48h" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = support.close()
    }

    @Test
    fun `hours from while usage was not allowed are never sent, also once it is allowed again`() {
        val hour = EpistolaClock.instant().truncatedTo(ChronoUnit.HOURS)
        // The installation has reported before: the hour before this one went out.
        withMediator { SetSharingRestriction(USAGE_SECTION, SharingRestriction.REQUIRED, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        EpistolaClock.withInstant(hour.plusSeconds(30)) { reporter.report() }
        assertThat(support.usageReports.keys).contains(hour.minus(1, ChronoUnit.HOURS).toString())
        tenantWithUse("Usage Not Allowed Then")
        withMediator { SetSharingRestriction(USAGE_SECTION, SharingRestriction.NOT_ALLOWED, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        assertThat(reportNextHour()).isEmpty()

        // An hour later usage is Required again: the hour of the use was under Not allowed, and stays unsent.
        withMediator { SetSharingRestriction(USAGE_SECTION, SharingRestriction.REQUIRED, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        EpistolaClock.withInstant(hour.plus(2, ChronoUnit.HOURS).plusSeconds(30)) { reporter.report() }

        assertThat(support.usageReports.keys).`as`("the not-allowed hour").doesNotContain(hour.toString())
    }

    @Test
    fun `a 403 insufficient_scope on a report tells the connection, which stops using usage`() {
        tenantWithUse("Usage Withdrawn")
        withMediator { SetSharingRestriction(USAGE_SECTION, SharingRestriction.REQUIRED, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        support.fault(app.epistola.suite.testing.FakeSupportFault.Respond(app.epistola.suite.testing.FakeSupportResponse.insufficientScope("usage")), "PUT", "/api/v1/usage/hours/")

        reportNextHour()

        assertThat(lastRound?.stoppedBecause).isEqualTo("the usage service is not granted")
        assertThat(connection.hasScope(app.epistola.suite.support.connection.SupportScope.USAGE)).`as`("the refusal is stored").isFalse()
        support.clearFaults()
    }

    @Test
    fun `an hour the support service refuses for good is skipped, so later hours still go`() {
        tenantWithUse("Usage Refused Hour")
        withMediator { SetSharingRestriction(USAGE_SECTION, SharingRestriction.REQUIRED, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        statistics.rollUp()
        previews.flush()
        val hour = EpistolaClock.instant().truncatedTo(ChronoUnit.HOURS)
        support.fault(app.epistola.suite.testing.FakeSupportFault.Respond(app.epistola.suite.testing.FakeSupportResponse.json(422, """{"error":"invalid_report"}""")), "PUT", "/api/v1/usage/hours/$hour", times = 1)

        val round = EpistolaClock.withInstant(hour.plus(2, ChronoUnit.HOURS).plusSeconds(30)) { reporter.report() }

        assertThat(support.usageReports.keys).`as`("round: %s", round).contains(hour.plus(1, ChronoUnit.HOURS).toString())
        val again = EpistolaClock.withInstant(hour.plus(2, ChronoUnit.HOURS).plusSeconds(60)) { reporter.report() }
        assertThat(again.sent).`as`("the refused hour is not tried again").isEmpty()
        support.clearFaults()
    }

    private fun reportedHours(): List<Instant> = jdbi.withHandle<List<Instant>, Exception> {
        it.createQuery("SELECT hour FROM support_usage_reports ORDER BY hour").map { rs, _ -> rs.getObject("hour", OffsetDateTime::class.java).toInstant() }.list()
    }

    @Test
    fun `which hours went out is kept only as long as a round can look back, and a day more`() {
        withMediator { SetSharingRestriction(USAGE_SECTION, SharingRestriction.REQUIRED, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        val hour = EpistolaClock.instant().truncatedTo(ChronoUnit.HOURS)
        EpistolaClock.withInstant(hour.plusSeconds(30)) { reporter.report() }
        EpistolaClock.withInstant(hour.plus(1, ChronoUnit.HOURS).plusSeconds(30)) { reporter.report() }
        assertThat(reportedHours()).containsExactly(hour.minus(1, ChronoUnit.HOURS), hour)

        // The backlog (configured as 48 hours here) and a day later: the earlier hour is out of reach of any round, the later one just not.
        val later = hour.plus(java.time.Duration.ofHours(48)).plus(UsageReporter.KEPT_BEYOND_BACKLOG)
        EpistolaClock.withInstant(later.plusSeconds(30)) { reporter.report() }

        assertThat(reportedHours()).`as`("hours a round can no longer reach are forgotten").doesNotContain(hour.minus(1, ChronoUnit.HOURS)).contains(hour)
    }

    @Test
    fun `the backlog never reaches hours the statistics no longer keep by the hour`() {
        assertThat(UsageReporter.backlog(java.time.Duration.ofHours(72), hourlyRetention = java.time.Duration.ofDays(7))).isEqualTo(java.time.Duration.ofHours(72))
        assertThat(UsageReporter.backlog(java.time.Duration.ofHours(72), hourlyRetention = java.time.Duration.ofDays(1))).isEqualTo(java.time.Duration.ofHours(23))
    }
}
