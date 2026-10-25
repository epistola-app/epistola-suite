// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.catalog.ProtocolMapper
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.model.DataExample
import org.springframework.stereotype.Component

/**
 * Writes one catalog resource, as the protocol models it, into the version the transaction targets:
 * created, or updated in place when the version has it. Shared by everything that puts released
 * content in a version — an archive's import ([ImportCatalogZip]) and a merge taking a release's
 * resource — so both write a resource the same way.
 *
 * [binaries] gives the bytes the resource names by archive path (an image's content, a font's faces).
 * Throws when a resource cannot be written; the caller decides whether that fails the whole.
 */
@Component
class CatalogResourceInstaller(
    private val protocolMapper: ProtocolMapper,
    private val references: app.epistola.suite.catalog.CatalogReferences,
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
    ): InstallStatus = installAs(catalog, resource, version, binaries) { alias -> references.resolveReceived(catalog, alias, declarations) }

    private fun installAs(
        catalog: CatalogId,
        resource: CatalogResource,
        version: String,
        binaries: (String) -> ByteArray?,
        resolve: (String?) -> CatalogId?,
    ): InstallStatus = when (resource) {
        is TemplateResource -> {
            val input = templateImportInput(resource, version, protocolMapper, resolve)
            val results = ImportTemplates(
                catalog = catalog,
                templates = listOf(input),
            ).execute()
            val result = results.first()
            if (result.status == ImportStatus.FAILED) {
                throw RuntimeException("Import failed for '${resource.key}': ${result.errorMessage}")
            }
            if (result.status == ImportStatus.CREATED) InstallStatus.INSTALLED else InstallStatus.UPDATED
        }

        is ThemeResource -> ImportTheme(
            catalog = catalog,
            slug = resource.key,
            name = resource.name,
            description = resource.description,
            documentStyles = protocolMapper.mapToDocumentStyles(resource.documentStyles),
            pageSettings = resource.pageSettings,
            blockStylePresets = protocolMapper.mapToBlockStylePresets(resource.blockStylePresets),
            spacingUnit = resource.spacingUnit,
        ).execute()

        is StencilResource -> ImportStencil(
            catalog = catalog,
            slug = resource.key,
            name = resource.name,
            description = resource.description,
            tags = resource.tags,
            content = resource.content,
            parameterSchema = resource.parameterSchema,
            readsTemplateData = resource.readsTemplateData,
        ).execute()

        is AttributeResource -> {
            val bindingCatalog = resource.codeListBinding?.let { resolve(it.catalogKey) }
            val bindingSlug = resource.codeListBinding?.key?.let(CodeListKey::of)
            ImportAttribute(
                catalog = catalog,
                slug = resource.key,
                displayName = resource.name,
                allowedValues = resource.allowedValues,
                codeListCatalog = bindingCatalog,
                codeListSlug = bindingSlug,
            ).execute()
        }

        is app.epistola.catalog.protocol.CodeListResource -> ImportCodeList(
            catalog = catalog,
            slug = resource.key,
            displayName = resource.name,
            description = resource.description,
            entries = resource.entries.map { wire ->
                app.epistola.suite.attributes.codelists.model.CodeListEntry(
                    code = wire.code,
                    label = wire.label,
                    sortOrder = wire.sortOrder,
                    hidden = wire.hidden,
                )
            },
        ).execute()

        is FontResource -> app.epistola.suite.fonts.commands.ImportFont(
            catalog = catalog,
            slug = resource.key,
            name = resource.name,
            kind = resource.kind,
            // Every face's file rode the archive; it is the font's own binary (ADR 0033).
            variants = resource.variants.map { entry ->
                val faceBytes = binaries(entry.contentPath())
                    ?: throw IllegalArgumentException("Missing font face content: ${entry.contentPath()}")
                app.epistola.suite.fonts.commands.ImportFontVariant(entry.weight, entry.italic, entry.mediaType, faceBytes)
            },
        ).execute()

        is ImageResource -> {
            // Resolve binary content from ZIP entries
            val contentPath = resource.contentPath()
            val contentBytes = binaries(contentPath)
                ?: throw IllegalArgumentException("Missing image content: ${resource.contentPath()}")
            val mediaType = AssetMediaType.fromMimeType(resource.mediaType)
            // A published catalog may name its images the way a person would -- `municipality-mark`
            // -- and this accepts them. It used to parse the slug as a UUID, because the REST
            // surface could not represent anything else; `/images` addresses an image by `slug`,
            // a plain string, and the asset operations that forced the UUID are gone.
            val assetId = AssetKey.of(resource.key)
            ImportAsset(
                catalog = catalog,
                id = assetId,
                name = resource.name,
                mediaType = mediaType,
                content = contentBytes,
                width = resource.width,
                height = resource.height,
            ).execute()
        }
    }
}

/**
 * A received template as [ImportTemplates] writes it: every member the protocol carries, the wire v8
 * ones (description, a variant's metadata, a data example's key) included. The one translation, so
 * everything that puts a received template in a version stores the same members.
 */
internal fun templateImportInput(
    resource: TemplateResource,
    version: String,
    protocolMapper: ProtocolMapper,
    /** The catalog an alias of [resource] means, its own without one. */
    resolve: (String?) -> CatalogId?,
): ImportTemplateInput = ImportTemplateInput(
    slug = resource.key,
    name = resource.name,
    version = version,
    themeId = resource.themeKey,
    themeCatalog = if (resource.themeKey != null) resolve(resource.themeCatalogKey) else null,
    pdfaEnabled = resource.pdfaEnabled,
    dataModel = protocolMapper.toObjectNode(resource.dataModel),
    dataExamples = resource.dataExamples?.map {
        DataExample(id = java.util.UUID.randomUUID().toString(), name = it.name, data = protocolMapper.toObjectNode(it.data)!!, key = it.key)
    } ?: emptyList(),
    templateModel = resource.templateModel,
    variants = resource.variants.map { variant ->
        ImportVariantInput(
            id = variant.key,
            title = variant.title,
            attributes = variant.attributes ?: emptyMap(),
            templateModel = variant.templateModel,
            isDefault = variant.isDefault,
            metadata = variant.metadata,
        )
    },
    description = resource.description,
)
