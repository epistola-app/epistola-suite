// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import

/**
 * Verifies the mediator's per-command transaction contract:
 *
 * - The command handler, IMMEDIATE event handlers, and event publication run in ONE
 *   transaction: a throwing IMMEDIATE handler rolls back the command's writes — even
 *   when the handler manages its writes through a nested `jdbi.inTransaction` (as
 *   [CreateStencil]'s handler does), which joins the surrounding transaction via
 *   SpringAwareTransactionHandler instead of committing early.
 * - The event_log write (AFTER_COMMIT) is atomic with the command: no event row is
 *   recorded for a rolled-back command.
 */
@Import(CommandTransactionIntegrationTest.RollbackTriggeringEventHandler::class)
class CommandTransactionIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `a throwing IMMEDIATE event handler rolls back the command's writes and its event_log entry`() {
        val tenant = createTenant("Tx Rollback Tenant")

        withMediator {
            val catalogKey = CatalogKey.of("tx-rollback-cat")
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Tx Rollback Catalog").execute()

            assertThatThrownBy {
                CreateStencil(
                    id = StencilId(StencilKey.of("tx-rollback-stencil"), CatalogId(catalogKey, TenantId(tenant.id))),
                    name = ROLLBACK_MARKER,
                ).execute()
            }.hasMessageContaining("Simulated IMMEDIATE event-handler failure")
        }

        val stencilCount = jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) FROM stencils WHERE name = :name")
                .bind("name", ROLLBACK_MARKER)
                .mapTo(Int::class.java)
                .one()
        }
        assertThat(stencilCount)
            .describedAs("stencil written by the rolled-back command must not survive")
            .isZero()

        val eventCount = jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) FROM event_log WHERE event_type = 'CreateStencil' AND payload::text LIKE :marker")
                .bind("marker", "%$ROLLBACK_MARKER%")
                .mapTo(Int::class.java)
                .one()
        }
        assertThat(eventCount)
            .describedAs("event_log must not record a command that rolled back")
            .isZero()
    }

    @Test
    fun `a successful command with a nested jdbi transaction commits and is event-logged`() {
        val tenant = createTenant("Tx Commit Tenant")

        withMediator {
            val catalogKey = CatalogKey.of("tx-commit-cat")
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Tx Commit Catalog").execute()
            CreateStencil(
                id = StencilId(StencilKey.of("tx-commit-stencil"), CatalogId(catalogKey, TenantId(tenant.id))),
                name = COMMIT_MARKER,
            ).execute()
        }

        val stencilCount = jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) FROM stencils WHERE name = :name")
                .bind("name", COMMIT_MARKER)
                .mapTo(Int::class.java)
                .one()
        }
        assertThat(stencilCount).isEqualTo(1)

        val eventCount = jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) FROM event_log WHERE event_type = 'CreateStencil' AND payload::text LIKE :marker")
                .bind("marker", "%$COMMIT_MARKER%")
                .mapTo(Int::class.java)
                .one()
        }
        assertThat(eventCount).isEqualTo(1)
    }

    class RollbackTriggeringEventHandler : EventHandler<CreateStencil> {
        override val phase = EventPhase.IMMEDIATE
        override fun on(event: CreateStencil, result: Any?) {
            if (event.name == ROLLBACK_MARKER) {
                error("Simulated IMMEDIATE event-handler failure")
            }
        }
    }

    companion object {
        private const val ROLLBACK_MARKER = "tx-contract rollback marker"
        private const val COMMIT_MARKER = "tx-contract commit marker"
    }
}
