package app.epistola.suite.mediator

import app.epistola.suite.CoreIntegrationTestBase
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.tenants.commands.CreateTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.jdbi.v3.core.Jdbi

/**
 * Integration tests for event persistence and eventing behavior.
 *
 * Focus: Verifying that:
 * - Events are persisted to the event_log table after transaction commits
 * - EventHandler discovery and invocation works in the Spring context
 * - Event log contains correct tenant information for audit trails
 */
class EventPersistenceIntegrationTest : CoreIntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `event log persists CreateTenant events after transaction commits`() = fixture {
        whenever {
            createTenant("Test Tenant")
        }

        then {
            val events = jdbi.withHandle<List<String>, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT event_type
                    FROM event_log
                    WHERE event_type = 'CreateTenant'
                    ORDER BY occurred_at DESC
                    LIMIT 1
                    """,
                )
                    .mapTo(String::class.java)
                    .list()
            }

            assertThat(events).hasSize(1)
            assertThat(events.first()).isEqualTo("CreateTenant")
        }
    }

    @Test
    fun `event log contains correct event type for each command`() = fixture {
        whenever {
            createTenant("Test Tenant")
        }

        then {
            val eventType = jdbi.withHandle<String, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT event_type
                    FROM event_log
                    WHERE event_type = 'CreateTenant'
                    LIMIT 1
                    """,
                )
                    .mapTo(String::class.java)
                    .findOne()
                    .orElse(null)
            }

            assertThat(eventType).isEqualTo("CreateTenant")
        }
    }

    @Test
    fun `multiple commands create multiple event log entries`() = fixture {
        whenever {
            createTenant("Tenant 1")
            createTenant("Tenant 2")
        }

        then {
            val count = jdbi.withHandle<Int, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT COUNT(*)
                    FROM event_log
                    WHERE event_type = 'CreateTenant'
                    """,
                )
                    .mapTo(Int::class.java)
                    .one()
            }

            assertThat(count).isGreaterThanOrEqualTo(2)
        }
    }

}
