// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query

/**
 * Declares that [catalogKey] depends on the tenant's catalog [on], selecting [version], next to what
 * it declares already (replacing an earlier declaration of [on]). Runs inside a mediator scope.
 */
fun declareDependency(tenantKey: TenantKey, catalogKey: CatalogKey, on: CatalogKey, version: String = "*"): List<CatalogDependencyDeclaration> {
    val target = requireNotNull(GetCatalog(tenantKey, on).query()) { "No catalog '${on.value}'" }
    val others = ListCatalogDependencies(tenantKey, catalogKey).query()
        .filter { it.catalogKey != on.value }
        .map { DeclaredCatalogDependency(it.registry, it.namespace, it.catalogKey, it.version) }
    return UpdateCatalogDependencies(
        tenantKey,
        catalogKey,
        others + DeclaredCatalogDependency(target.registry, target.namespace, on.value, version),
    ).execute()
}
