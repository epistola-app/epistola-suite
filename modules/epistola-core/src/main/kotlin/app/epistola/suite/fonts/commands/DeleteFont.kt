// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.commands

import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.fonts.FontStore
import app.epistola.suite.fonts.model.FontInUseException
import app.epistola.suite.fonts.queries.FindFontUsages
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Deletes a font family and the uploaded binaries it owned.
 *
 * Mirrors `app.epistola.suite.assets.commands.DeleteAsset`:
 *  - `requireCatalogEditable` — a catalog that cannot be changed here (e.g. `system`) rejects the
 *    delete with `CatalogReadOnlyException`, the same exception every other
 *    catalog-mutating command throws.
 *  - Usage guard — a font referenced by a theme or template refuses with
 *    [FontInUseException] (mirrors `AssetInUseException`).
 *
 * A font is a draft row naming its content (ADR 0033), and its faces' files are binaries of that
 * content, not assets: deleting the row is the whole delete, and the files go once no content names
 * them any more (the content collector).
 *
 */
data class DeleteFont(
    val fontId: FontId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey get() = fontId.tenantKey
}

@Component
class DeleteFontHandler(
    private val jdbi: Jdbi,
    private val fonts: FontStore,
) : CommandHandler<DeleteFont, Boolean> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: DeleteFont): Boolean {
        val catalog = command.fontId.catalogId
        // Catalog editability — one Epistola supplies (e.g. `system`) rejects.
        requireCatalogEditable(catalog)

        if (jdbi.withHandle<Boolean, Exception> { fonts.get(it, command.fontId) == null }) return false
        val usages = FindFontUsages(command.fontId).query()
        if (usages.isNotEmpty()) throw FontInUseException(command.fontId.key, usages)

        // Its faces' files are its binaries: they go when nothing names them any more (the content collector).
        val deleted = jdbi.withHandle<Boolean, Exception> { fonts.delete(it, command.fontId) }
        logger.info("Deleted font {} ({}) for tenant {}", command.fontId.key, catalog.key, command.fontId.tenantKey)
        return deleted
    }
}
