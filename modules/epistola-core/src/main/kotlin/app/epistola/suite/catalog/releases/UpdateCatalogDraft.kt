// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Updates what a person sets on a draft (contract `updateCatalogDraft`): its [name], [defaultTheme]
 * and [presets], each left as it is when null. All of it in one transaction, under one check of the
 * revision the caller read ([expectedRevision], null: no check), so a stale update changes nothing.
 * The next version has no name of its own ([RenameCatalogDraft]).
 */
data class UpdateCatalogDraft(
    val catalog: CatalogId,
    val draft: DraftRef,
    val name: String? = null,
    /** The draft's new default theme; null keeps it ([clearDefaultTheme] removes it). */
    val defaultTheme: CatalogDefaultTheme? = null,
    val presets: List<CatalogPreset>? = null,
    val expectedRevision: Long? = null,
    /** Removes the default theme, so a template that names none renders with the rendering defaults alone. */
    val clearDefaultTheme: Boolean = false,
) : Command<UpdatedCatalogDraft>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.TEMPLATE_EDIT

    init {
        validate("clearDefaultTheme", !(clearDefaultTheme && defaultTheme != null)) { "Send a defaultTheme or clearDefaultTheme, not both" }
    }
}

/** The draft's revision after [UpdateCatalogDraft]: what the caller names to update it again. */
data class UpdatedCatalogDraft(val revision: Long)

@Component
class UpdateCatalogDraftHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
) : CommandHandler<UpdateCatalogDraft, UpdatedCatalogDraft> {
    override fun handle(command: UpdateCatalogDraft): UpdatedCatalogDraft {
        val draft = jdbi.withHandle<CatalogDraft, Exception> { handle ->
            val draft = drafts.find(handle, command.catalog, command.draft)
                ?: throw CatalogReleaseNotFoundException(command.catalogKey, command.draft.toString())
            drafts.requireRevision(handle, command.tenantKey, draft.versionId, command.expectedRevision, "Catalog version '${draft.label}' of '${command.catalogKey.value}'")
            draft
        }
        val version = CatalogReleaseRef(command.catalog, draft.ref)
        command.name?.let { RenameCatalogDraft(command.catalog, draft.ref, it).execute() }
        command.defaultTheme?.let { SetCatalogDefaultTheme(command.catalog, it, catalogVersion = version).execute() }
        if (command.clearDefaultTheme) SetCatalogDefaultTheme(command.catalog, theme = null, catalogVersion = version).execute()
        command.presets?.let { SetCatalogPresets(command.catalog, it, catalogVersion = version).execute() }
        return jdbi.withHandle<UpdatedCatalogDraft, Exception> { handle ->
            UpdatedCatalogDraft(requireNotNull(drafts.find(handle, command.catalog, draft.ref)).revision)
        }
    }
}
