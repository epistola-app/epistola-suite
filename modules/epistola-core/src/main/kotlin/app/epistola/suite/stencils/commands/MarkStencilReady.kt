// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.commands

import app.epistola.suite.catalog.ready.ReadyMarks
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.StencilFitCheck
import app.epistola.suite.stencils.StencilNotFoundException
import app.epistola.suite.stencils.stencilAtAddress
import app.epistola.suite.templates.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Records that the stencil's current content is reviewed and may be released (contract
 * `markStencilReady`). Templates use a stencil by reference (ADR 0006), so nothing is held back:
 * which templates still fit it is the usage page's answer, and releasing checks every instance.
 * Refused while a stencil instance in its own content does not fit.
 */
data class MarkStencilReady(
    val stencilId: StencilId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Command<Unit>,
    RequiresPermission {
    override val permission = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = stencilId.tenantKey
}

@Component
class MarkStencilReadyHandler(
    private val jdbi: Jdbi,
    private val readyMarks: ReadyMarks,
    private val stencilFit: StencilFitCheck,
    private val objectMapper: ObjectMapper,
) : CommandHandler<MarkStencilReady, Unit> {
    override fun handle(command: MarkStencilReady) {
        val stencil = command.stencilId
        requireCatalogEditable(stencil.tenantKey, stencil.catalogKey)
        jdbi.useTransaction<Exception> { handle ->
            val row = handle.createQuery(
                "SELECT resource_id, content::text AS content FROM stencils WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")} AND resource_id = ${stencilAtAddress("t", "c", "k")} FOR UPDATE",
            )
                .bind("t", stencil.tenantKey)
                .bind("c", stencil.catalogKey)
                .bind("k", stencil.key)
                .map { rs, _ -> rs.getObject("resource_id", UUID::class.java) to rs.getString("content") }
                .findOne()
                .orElseThrow { StencilNotFoundException(stencil.tenantKey, stencil.key) }
            val content = objectMapper.readValue(row.second, TemplateDocument::class.java)
            stencilFit.requireInstancesFit(stencil.tenantKey, stencil.catalogKey.value, content, "Cannot mark stencil '${stencil.key.value}' ready")
            readyMarks.mark(handle, stencil.tenantKey, row.first)
        }
    }
}
