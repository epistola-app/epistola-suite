// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.catalog.protocol.StencilResource
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.types.Arrival
import app.epistola.suite.catalog.types.ResourceType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.ImportStencil
import org.springframework.stereotype.Component

/** A stencil as a catalog resource type (ADR 0039): its document names a theme, fonts and images. */
@Component
class StencilResourceType : ResourceType<StencilResource> {
    override val name = StencilStore.STENCIL
    override val wireClass = StencilResource::class.java
    override val installedAfter = setOf("theme", "font", "image")

    override fun receive(catalog: CatalogId, resource: StencilResource, arrival: Arrival): InstallStatus = ImportStencil(
        catalog = catalog,
        slug = resource.key,
        name = resource.name,
        description = resource.description,
        tags = resource.tags,
        content = resource.content,
        parameterSchema = resource.parameterSchema,
        readsTemplateData = resource.readsTemplateData,
        // As received: none clears what the draft held.
        metadata = resource.metadata.orEmpty(),
    ).execute()
}
