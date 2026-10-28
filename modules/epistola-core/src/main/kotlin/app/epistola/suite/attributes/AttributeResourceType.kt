// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.suite.attributes.codelists.CodeListStore
import app.epistola.suite.attributes.commands.ImportAttribute
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.types.Arrival
import app.epistola.suite.catalog.types.ResourceType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.mediator.execute
import org.springframework.stereotype.Component

/** A variant attribute as a catalog resource type (ADR 0039): it may bind a code list, by its catalog's alias. */
@Component
class AttributeResourceType : ResourceType<AttributeResource> {
    override val name = AttributeStore.ATTRIBUTE
    override val wireClass = AttributeResource::class.java
    override val installedAfter = setOf(CodeListStore.CODE_LIST)

    override fun receive(catalog: CatalogId, resource: AttributeResource, arrival: Arrival): InstallStatus = ImportAttribute(
        catalog = catalog,
        slug = resource.key,
        displayName = resource.name,
        allowedValues = resource.allowedValues,
        codeListCatalog = resource.codeListBinding?.let { arrival.resolve(it.catalogKey) },
        codeListSlug = resource.codeListBinding?.key?.let(CodeListKey::of),
        // As received: none clears what the draft held.
        metadata = resource.metadata.orEmpty(),
    ).execute()
}
