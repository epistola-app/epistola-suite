// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.revisions.ResourceRevisionStore
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Drops the content a release retained, keeping the release itself.
 *
 * The only way to reclaim what releases hold. A release is never deleted and its revisions are
 * never collected, so content accumulates for the life of an installation — with no lever for an
 * author who released an image by mistake, or who is asked to remove something and finds that
 * deleting it from the working copy leaves every release that carried it holding it still.
 *
 * **The release survives; only its content goes.** Version, date, notes and fingerprint stay, which
 * is what keeps the history honest: the fingerprint remains the evidence of what that version was,
 * for whoever installed it. What the release loses is the ability to be exported or published as
 * released — it becomes a release cut before Epistola kept release content, a state every screen
 * already understands and reports as "not kept". Reusing it is why this adds no new vocabulary to
 * the UI.
 *
 * Deliberately not reversible, and not pretending to be. The content is gone; re-releasing the
 * working copy is a new version, not this one restored.
 */
data class ForgetReleaseContent(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
) : Command<Boolean>,
    RequiresPermission {
    /** Removing content for good is catalog management, not the authoring act that releasing is. */
    override val permission get() = Permission.CATALOG_MANAGE
}

@Component
class ForgetReleaseContentHandler(
    private val jdbi: Jdbi,
    private val revisions: ResourceRevisionStore,
) : CommandHandler<ForgetReleaseContent, Boolean> {

    /**
     * @return whether this call is what forgot it. False for a release that does not exist, and for
     *   one that already kept nothing — so a second click, or two administrators clicking at once,
     *   reports honestly instead of claiming to have collected something.
     */
    override fun handle(command: ForgetReleaseContent): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val forgotten = handle.createUpdate(
            """
            UPDATE catalog_releases
            SET content_retained = FALSE
            WHERE tenant_key = :t AND catalog_key = :c AND version = :v AND content_retained
            """,
        )
            .bind("t", command.tenantKey)
            .bind("c", command.catalogKey)
            .bind("v", command.version)
            .execute() > 0

        if (!forgotten) return@withHandle false

        handle.createUpdate(
            """
            DELETE FROM release_entries
            WHERE tenant_key = :t AND catalog_key = :c AND version = :v
            """,
        )
            .bind("t", command.tenantKey)
            .bind("c", command.catalogKey)
            .bind("v", command.version)
            .execute()

        // Tenant-wide on purpose: a revision is shared by every release whose content is identical,
        // so what this release held is only collectable once no other release names it either.
        revisions.collectUnreferenced(handle, command.tenantKey)
        true
    }
}
