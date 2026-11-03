// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.contracts.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.ContentVersion
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.releases.versionOf
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.DataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.assignDataExampleKeys
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.readValue

/** The template's data contract as it is now, or null when there is no such template. */
data class GetDataContract(
    val templateId: TemplateId,
    /** The catalog version this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
) : ReleaseReadable,
    Query<DataContract?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = templateId.tenantKey
}

@Component
class GetDataContractHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<GetDataContract, DataContract?> {
    override fun handle(query: GetDataContract): DataContract? = jdbi.withHandle<DataContract?, Exception> { handle ->
        readDataContract(handle, objectMapper, handle.versionOf(query.templateId.catalogId, query), query.templateId)
    }
}

/**
 * Reads [template]'s data contract in version [at], a draft's or a release's. With [forUpdate] it is
 * about to be written, so its draft row is locked first.
 */
internal fun readDataContract(handle: Handle, objectMapper: ObjectMapper, at: ContentVersion, template: TemplateId, forUpdate: Boolean = false): DataContract? {
    if (forUpdate) app.epistola.suite.catalog.revisions.DraftResources.lockRevision(handle, at, app.epistola.suite.templates.TemplateStore.TEMPLATE, template.key.value) ?: return null
    return handle
        .createQuery(
            """
            SELECT data_model::text AS data_model, data_examples::text AS data_examples, revision FROM version_templates
            WHERE tenant_key = :t AND version_id = :v AND id = :k
            """,
        )
        .bind("t", template.tenantKey)
        .bindVersion("v", at.versionId)
        .bind("k", template.key)
        .map { rs, _ ->
            DataContract(
                dataModel = rs.getString("data_model")?.let { objectMapper.readValue(it, ObjectNode::class.java) },
                dataExamples = assignDataExampleKeys(objectMapper.readValue<List<DataExample>>(rs.getString("data_examples"))),
                revision = rs.getLong("revision"),
            )
        }
        .findOne()
        .orElse(null)
}
