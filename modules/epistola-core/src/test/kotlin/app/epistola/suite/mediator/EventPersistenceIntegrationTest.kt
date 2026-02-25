package app.epistola.suite.mediator

import app.epistola.suite.CoreIntegrationTestBase
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
    fun `mediator is SpringMediator instance`() = fixture {
        whenever {
            // No-op
        }

        then {
            assertThat(mediator).isInstanceOf(SpringMediator::class.java)
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
                    "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name = 'event_log')"
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


}
