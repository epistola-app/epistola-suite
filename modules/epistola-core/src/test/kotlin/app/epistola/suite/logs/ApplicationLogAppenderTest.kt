package app.epistola.suite.logs

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SecurityContext
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit cover for the Logback → [ApplicationLogRecord] conversion. No Spring, no
 * database: the appender is given a capturing sink and fed synthetic events.
 */
class ApplicationLogAppenderTest {

    // A standalone logback context, used only to mint loggers for synthetic LoggingEvents. We
    // deliberately do NOT pull the global context via `LoggerFactory.getILoggerFactory() as LoggerContext`:
    // under the full parallel suite that call can return the bootstrap `SubstituteLoggerFactory`
    // mid-initialization (not a logback `LoggerContext`), throwing a `ClassCastException`. The appender
    // under test converts the event it's handed and never consults the global context, so a fresh one
    // is equivalent and race-free.
    private val loggerContext = LoggerContext()

    private fun event(
        loggerName: String,
        level: Level,
        message: String,
        throwable: Throwable? = null,
        mdc: Map<String, String> = emptyMap(),
    ): LoggingEvent = LoggingEvent("fqcn", loggerContext.getLogger(loggerName), level, message, throwable, null).apply {
        // Always set the MDC map explicitly (even when empty). Otherwise logback's
        // LoggingEvent.getMDCPropertyMap() lazily reads the *global* SLF4J MDC adapter, which is null
        // until SLF4J's one-time init completes — a race under the full parallel suite. Setting it
        // here keeps the test fully decoupled from global SLF4J state.
        setMDCPropertyMap(mdc)
    }

    @Test
    fun `converts level, logger, message, thread, exception and MDC`() {
        val captured = mutableListOf<ApplicationLogRecord>()
        val appender = ApplicationLogAppender(captured::add)

        appender.append(
            event(
                loggerName = "com.example.Service",
                level = Level.WARN,
                message = "boom happened",
                throwable = RuntimeException("kaboom"),
                mdc = linkedMapOf("traceId" to "trace-1", "spanId" to "span-1", "requestId" to "req-9"),
            ),
        )

        val record = captured.single()
        assertThat(record.level).isEqualTo("WARN")
        assertThat(record.logger).isEqualTo("com.example.Service")
        assertThat(record.message).isEqualTo("boom happened")
        assertThat(record.thread).isNotBlank()
        assertThat(record.traceId).isEqualTo("trace-1")
        assertThat(record.spanId).isEqualTo("span-1")
        assertThat(record.exception).contains("kaboom")
        // traceId/spanId are promoted to columns and excluded from attributes.
        assertThat(record.attributes).containsEntry("requestId", "req-9")
        assertThat(record.attributes).doesNotContainKeys("traceId", "spanId")
        // No bound security context → a system (null-tenant) row.
        assertThat(record.tenantKey).isNull()
    }

    @Test
    fun `attributes is null when MDC carries only trace and span`() {
        val captured = mutableListOf<ApplicationLogRecord>()
        ApplicationLogAppender(captured::add).append(
            event("a.b.C", Level.INFO, "hi", mdc = mapOf("traceId" to "t", "spanId" to "s")),
        )
        assertThat(captured.single().attributes).isNull()
    }

    @Test
    fun `ignores events from its own package to avoid recursion`() {
        val captured = mutableListOf<ApplicationLogRecord>()
        val appender = ApplicationLogAppender(captured::add)

        appender.append(event("app.epistola.suite.logs.ApplicationLogIngestor", Level.ERROR, "db down"))

        assertThat(captured).isEmpty()
    }

    @Test
    fun `MDC tenant hints are ignored — attribution comes only from the principal`() {
        val captured = mutableListOf<ApplicationLogRecord>()
        ApplicationLogAppender(captured::add).append(
            event("a.b.C", Level.INFO, "no principal bound", mdc = mapOf("tenantKey" to "sneaky", "tenantId" to "sneaky2")),
        )
        val record = captured.single()
        // No bound principal → system row, regardless of MDC tenant hints.
        assertThat(record.tenantKey).isNull()
        // The MDC keys are still preserved as attributes (only trace/span are promoted out).
        assertThat(record.attributes).containsEntry("tenantKey", "sneaky").containsEntry("tenantId", "sneaky2")
    }

    @Test
    fun `captures the active request tenant from the security context`() {
        val captured = mutableListOf<ApplicationLogRecord>()
        val appender = ApplicationLogAppender(captured::add)

        SecurityContext.runWithPrincipal(principalForTenant("acme-corp")) {
            appender.append(event("com.example.Service", Level.INFO, "in request"))
        }

        assertThat(captured.single().tenantKey).isEqualTo("acme-corp")
    }

    private fun principalForTenant(tenant: String): EpistolaPrincipal = EpistolaPrincipal(
        userId = UserKey(UUID.fromString("00000000-0000-0000-0000-000000000001")),
        externalId = "ext",
        email = "u@example.com",
        displayName = "U",
        tenantMemberships = emptyMap(),
        currentTenantId = TenantKey.of(tenant),
    )
}
