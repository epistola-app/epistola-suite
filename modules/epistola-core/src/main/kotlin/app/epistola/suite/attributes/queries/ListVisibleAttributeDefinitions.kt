// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.queries

import app.epistola.suite.attributes.ATTRIBUTE_COLUMNS
import app.epistola.suite.attributes.AttributeRows
import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.catalog.dependencies.VisibleVersions
import app.epistola.suite.catalog.dependencies.VisibleVersions.bindVisible
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * The attributes a variant in one version of [catalog] can carry ([VisibleVersions]): its own
 * catalog's, then those of each catalog it declares, at the release or draft the declaration names;
 * within a catalog by name. What the variant attribute pickers offer.
 */
data class ListVisibleAttributeDefinitions(
    val catalog: CatalogId,
    /** The catalog version this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
) : ReleaseReadable,
    Query<List<VariantAttributeDefinition>>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = catalog.tenantKey
}

@Component
class ListVisibleAttributeDefinitionsHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<ListVisibleAttributeDefinitions, List<VariantAttributeDefinition>> {
    override fun handle(query: ListVisibleAttributeDefinitions): List<VariantAttributeDefinition> = jdbi.withHandle<List<VariantAttributeDefinition>, Exception> { handle ->
        val visible = VisibleVersions.of(handle, query.catalog, query)
        if (visible.isEmpty()) return@withHandle emptyList()
        handle.createQuery(
            "SELECT $ATTRIBUTE_COLUMNS FROM version_attributes t WHERE t.tenant_key = :t AND ${VisibleVersions.predicate("t")} " +
                "ORDER BY ${VisibleVersions.order("t")}, t.display_name ASC, t.id",
        )
            .bind("t", query.tenantKey)
            .bindVisible(visible)
            .map(AttributeRows.of(handle, objectMapper, query.tenantKey))
            .list()
    }
}
