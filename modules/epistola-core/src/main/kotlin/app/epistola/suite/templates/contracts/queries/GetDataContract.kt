// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.contracts.queries

import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.DataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.templateAtAddress
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.readValue

/** The template's data contract as it is now, or null when there is no such template. */
data class GetDataContract(
    val templateId: TemplateId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
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
        readDataContract(handle, objectMapper, query.templateId)
    }
}

/** Reads [template]'s data contract on [handle], locking the row with [forUpdate]. */
internal fun readDataContract(handle: Handle, objectMapper: ObjectMapper, template: TemplateId, forUpdate: Boolean = false): DataContract? = handle
    .createQuery(
        """
        SELECT data_model::text AS data_model, data_examples::text AS data_examples FROM document_templates
        WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")} AND resource_id = ${templateAtAddress("t", "c", "k")}
        ${if (forUpdate) "FOR UPDATE" else ""}
        """,
    )
    .bind("t", template.tenantKey)
    .bind("c", template.catalogKey)
    .bind("k", template.key)
    .map { rs, _ ->
        DataContract(
            dataModel = rs.getString("data_model")?.let { objectMapper.readValue(it, ObjectNode::class.java) },
            dataExamples = objectMapper.readValue<List<DataExample>>(rs.getString("data_examples")),
        )
    }
    .findOne()
    .orElse(null)
