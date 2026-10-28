// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.catalog.ProtocolMapper
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.types.Arrival
import app.epistola.suite.catalog.types.ResourceType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.mediator.execute
import app.epistola.suite.themes.commands.ImportTheme
import org.springframework.stereotype.Component

/** A theme as a catalog resource type (ADR 0039): its styles name fonts and images. */
@Component
class ThemeResourceType(private val protocolMapper: ProtocolMapper) : ResourceType<ThemeResource> {
    override val name = ThemeStore.THEME
    override val wireClass = ThemeResource::class.java
    override val installedAfter = setOf("font", "image")

    override fun receive(catalog: CatalogId, resource: ThemeResource, arrival: Arrival): InstallStatus = ImportTheme(
        catalog = catalog,
        slug = resource.key,
        name = resource.name,
        description = resource.description,
        documentStyles = protocolMapper.mapToDocumentStyles(resource.documentStyles),
        pageSettings = resource.pageSettings,
        blockStylePresets = protocolMapper.mapToBlockStylePresets(resource.blockStylePresets),
        spacingUnit = resource.spacingUnit,
        defaultBlockStyles = resource.defaultBlockStyles,
        // As received: none clears what the draft held.
        metadata = resource.metadata.orEmpty(),
    ).execute()
}
