// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.metrics.MetricsRecordingMediator
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Integration tests for event persistence and eventing behavior.
 *
 * Focus: Verifying that:
 * - Events are persisted to the event_log table after transaction commits
 * - EventHandler discovery and invocation works in the Spring context
 * - Event log contains correct tenant information for audit trails
 */
class EventPersistenceIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `mediator is SpringMediator instance`() = fixture {
        whenever {
            // No-op
        }

        then {
            // In tests the mediator is wrapped by the metrics-recording decorator
            // (see MediatorMetricsConfiguration); the real dispatcher it delegates to
            // is still the SpringMediator that publishes events / persists the audit log.
            val dispatcher = (mediator as? MetricsRecordingMediator)?.delegate ?: mediator
            assertThat(dispatcher).isInstanceOf(SpringMediator::class.java)
        }
    }

    @Test
    fun `event_log table exists`() = fixture {
        whenever {
            // No-op - just check table exists
        }

        then {
            val tableExists = jdbi.withHandle<Boolean, Exception> { handle ->
                handle.createQuery(
                    "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name = 'event_log')",
                )
                    .mapTo(Boolean::class.java)
                    .one()
            }

            assertThat(tableExists).isTrue()
        }
    }

    @Test
    fun `CreateTenant command executes successfully`() = fixture {
        var tenant: app.epistola.suite.tenants.Tenant? = null

        whenever {
            tenant = createTenant("Test Tenant")
        }

        then {
            assertThat(tenant).isNotNull()
            assertThat(tenant!!.name).isEqualTo("Test Tenant")
        }
    }

    @Test
    fun `an ordinary command is recorded in event_log`() = fixture {
        whenever {
            createTenant("Logged Tenant")
        }

        then {
            // CreateTenant carries no opt-out marker, so the stream records it. (CreateTenant is
            // RequiresPlatformRole, not TenantScoped, so its event_log row has a NULL tenant_key —
            // match on event_type, which is the command's simple name.)
            val count = jdbi.withHandle<Int, Exception> { handle ->
                handle.createQuery("SELECT COUNT(*) FROM event_log WHERE event_type = 'CreateTenant'")
                    .mapTo(Int::class.java)
                    .one()
            }
            assertThat(count).isGreaterThanOrEqualTo(1)
        }
    }
}
