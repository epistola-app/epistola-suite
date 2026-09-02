// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.commands.CreateTenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import org.assertj.core.api.Assertions.assertThat as assertThatValue

/**
 * Covers the one seam the handler tests cannot: the template's `data-confirm-*` hooks
 * reaching `openConfirmDialog`, which builds the `hx-post` form that removes the node and
 * swaps the refreshed table back in. Nothing here is inline script, so a CSP violation
 * would fail this test through [BasePlaywrightTest].
 */
class ForgetClusterNodeUiTest : BasePlaywrightTest() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `forgetting a stale node removes its row from the cluster table`() {
        val tenant = withMediator {
            CreateTenant(
                id = TenantKey.of("forget-node-ui-${System.nanoTime()}"),
                name = "Forget Node UI Tenant",
            ).execute()
        }
        val nodeId = "ui-stale-node"
        insertNode(nodeId, OffsetDateTime.now().minusDays(2))

        gotoAndReady("/tenants/${tenant.id}/cluster")

        // Scope to the node table: a stale node also shows up in the per-node rows of every
        // each-capable-node scheduled task, which is part of the same leak.
        val nodesTable = page.locator("[data-testid='cluster-nodes-table']")
        val row = nodesTable.locator("tr", com.microsoft.playwright.Locator.LocatorOptions().setHasText(nodeId))
        assertThat(row).hasCount(1)

        val forget = row.locator("[data-testid='forget-node']")
        assertThat(forget).isVisible()

        val confirm = page.openDialogByTrigger(forget, "#confirm-dialog")
        confirm.locator("[data-testid='confirm-dialog-confirm']").click()

        // The fragment swap drops the row, and the registry row is really gone.
        assertThat(nodesTable.locator("tr", com.microsoft.playwright.Locator.LocatorOptions().setHasText(nodeId)))
            .hasCount(0)
        assertThatValue(nodeExists(nodeId)).isFalse()
    }

    @Test
    fun `the current node is offered no Forget button`() {
        val tenant = withMediator {
            CreateTenant(
                id = TenantKey.of("forget-node-ui-active-${System.nanoTime()}"),
                name = "Forget Node UI Active Tenant",
            ).execute()
        }

        gotoAndReady("/tenants/${tenant.id}/cluster")

        // Opening the page heartbeats this node, so its row is active — and an active node
        // must never be removable: every claim path needs its row.
        val currentRow = page.locator("[data-testid='cluster-nodes-table']")
            .locator("tr", com.microsoft.playwright.Locator.LocatorOptions().setHasText("current"))
        assertThat(currentRow.locator("[data-testid='forget-node']")).hasCount(0)
    }

    private fun insertNode(nodeId: String, lastSeenAt: OffsetDateTime) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO cluster_nodes (node_id, capabilities, joined_at, last_seen_at, metadata)
                VALUES (:nodeId, '["suite"]'::jsonb, :lastSeenAt, :lastSeenAt, '{}'::jsonb)
                ON CONFLICT (node_id) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
                """,
            )
                .bind("nodeId", nodeId)
                .bind("lastSeenAt", lastSeenAt)
                .execute()
        }
    }

    private fun nodeExists(nodeId: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery("SELECT EXISTS (SELECT 1 FROM cluster_nodes WHERE node_id = :nodeId)")
            .bind("nodeId", nodeId)
            .mapTo(Boolean::class.java)
            .one()
    }
}
