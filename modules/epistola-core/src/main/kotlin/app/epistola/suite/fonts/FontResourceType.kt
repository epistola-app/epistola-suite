// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.catalog.protocol.FontResource
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.types.Arrival
import app.epistola.suite.catalog.types.ResourceType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.mediator.execute
import org.springframework.stereotype.Component

/** A font as a catalog resource type (ADR 0039): its faces are binaries of its own, carried by the archive. */
@Component
class FontResourceType : ResourceType<FontResource> {
    override val name = FontStore.FONT
    override val wireClass = FontResource::class.java

    override fun receive(catalog: CatalogId, resource: FontResource, arrival: Arrival): InstallStatus = ImportFont(
        catalog = catalog,
        slug = resource.key,
        name = resource.name,
        kind = resource.kind,
        variants = resource.variants.map { entry ->
            val faceBytes = arrival.binaries(entry.contentPath())
                ?: throw IllegalArgumentException("Missing font face content: ${entry.contentPath()}")
            ImportFontVariant(entry.weight, entry.italic, entry.mediaType, faceBytes)
        },
        // As received: none clears what the draft held.
        metadata = resource.metadata.orEmpty(),
    ).execute()
}
