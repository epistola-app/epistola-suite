// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.versions.GetCatalogDraft
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query

/**
 * Declares that [catalogKey] depends on the tenant's catalog [on], pinning [release], or by default
 * its highest release, or, when it has none, its next version's draft (which releasing [on] turns
 * into a pin of the release it becomes), next to what it declares already (replacing an earlier
 * declaration of [on]). Runs inside a mediator scope.
 */
fun declareDependency(tenantKey: TenantKey, catalogKey: CatalogKey, on: CatalogKey, release: String? = null): List<CatalogDependencyDeclaration> {
    val target = requireNotNull(GetCatalog(tenantKey, on).query()) { "No catalog '${on.value}'" }
    val pinned = release ?: target.releasedVersion ?: target.installedReleaseVersion
    val draft = if (pinned == null) requireNotNull(GetCatalogDraft(tenantKey, on).query()) { "Catalog '${on.value}' has neither a release nor a draft" }.versionId else null
    val others = ListCatalogDependencies(tenantKey, catalogKey).query()
        .filter { it.catalogKey != on.value }
        .map { DeclaredCatalogDependency(it.registry, it.namespace, it.catalogKey, it.releaseVersion, it.draftId) }
    return UpdateCatalogDependencies(
        tenantKey,
        catalogKey,
        others + DeclaredCatalogDependency(target.registry, target.namespace, on.value, pinned, draft),
    ).execute()
}
