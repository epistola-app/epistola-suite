// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.queries.GetLatestCatalogRelease
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.contracts.queries.GetDataContract

/**
 * Marks [variantId]'s catalog ready and cuts its next release, so generation can render the variant.
 *
 * From 2.0 generation renders a catalog release and nothing else, so a test that generates a document
 * needs one. The template gets the minimum releasable data contract first, because marking it ready
 * requires an example.
 *
 * @return the version cut.
 */
fun Mediator.publishAndRelease(variantId: VariantId): String {
    // Only when the template has no example yet: a test that set its own contract keeps it.
    if (query(GetDataContract(variantId.templateId))?.dataExamples.isNullOrEmpty()) {
        ensureRequiredDataExample(variantId.templateId)
    }
    return releaseNext(variantId.templateId.catalogId)
}

/**
 * Marks every changed resource of [catalogId] ready and releases its next version under the number its
 * changes need (`1.0.0` for its first), as an author releasing what they finished would.
 *
 * @return the version cut.
 */
fun Mediator.releaseNext(catalogId: CatalogId): String {
    send(MarkCatalogReady(catalogId.tenantKey, catalogId.key))
    return send(ReleaseCatalogVersion(tenantKey = catalogId.tenantKey, catalogKey = catalogId.key)).version
}

/**
 * Deploys the latest release of [catalogId] to [environmentId], cutting the catalog's first release
 * when it has none. Environments serve catalog releases from 2.0; this replaces activating a version.
 *
 * @return the version deployed.
 */
fun Mediator.deployLatestRelease(environmentId: app.epistola.suite.common.ids.EnvironmentId, catalogId: CatalogId): String {
    val version = query(GetLatestCatalogRelease(catalogId.tenantKey, catalogId.key)).latestVersion ?: releaseNext(catalogId)
    send(app.epistola.suite.environments.commands.DeployRelease(environmentId, catalogId.key, version))
    return version
}
