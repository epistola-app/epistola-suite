// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.suite.catalog.types.Arrival
import app.epistola.suite.catalog.types.ResourceTypes
import app.epistola.suite.common.ids.CatalogId
import org.springframework.stereotype.Component

/**
 * Writes one catalog resource, as the protocol models it, into the version the transaction targets:
 * created, or updated in place when the version has it. Shared by everything that puts released
 * content in a version — an archive's import ([ImportCatalogZip]) and a merge taking a release's
 * resource — so both write a resource the same way. Each type receives its own (ADR 0039).
 *
 * [binaries] gives the bytes the resource names by archive path (an image's content, a font's faces).
 * Throws when a resource cannot be written; the caller decides whether that fails the whole.
 */
@Component
class CatalogResourceInstaller(
    private val references: app.epistola.suite.catalog.CatalogReferences,
    private val types: ResourceTypes,
) {

    /**
     * [declarations] are the dependencies [resource] arrives with, which name what its aliases mean
     * (ADR 0034, section 9); null when it arrives with none of its own (a merge writes into a draft
     * that declares them).
     */
    fun install(
        catalog: CatalogId,
        resource: CatalogResource,
        version: String,
        binaries: (String) -> ByteArray?,
        declarations: List<app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration>? = null,
    ): InstallStatus = types.receive(
        types.of(resource),
        catalog,
        resource,
        Arrival(version, binaries) { alias -> references.resolveReceived(catalog, alias, declarations) },
    )
}
