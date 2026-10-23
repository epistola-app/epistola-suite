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
) {

    fun install(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        resource: CatalogResource,
        version: String,
        binaries: (String) -> ByteArray?,
    ): InstallStatus = installAs(TenantId(tenantKey), catalogKey, resource, version, binaries)

    private fun installAs(
        tenantId: TenantId,
        catalogKey: CatalogKey,
        resource: CatalogResource,
        version: String,
        binaries: (String) -> ByteArray?,
    ): InstallStatus = when (resource) {
        is TemplateResource -> {
            val input = templateImportInput(resource, catalogKey, version, protocolMapper)
            val results = ImportTemplates(
                tenantId = tenantId,
                catalogKey = catalogKey,
                templates = listOf(input),
            ).execute()
            val result = results.first()
            if (result.status == ImportStatus.FAILED) {
                throw RuntimeException("Import failed for '${resource.key}': ${result.errorMessage}")
            }
            if (result.status == ImportStatus.CREATED) InstallStatus.INSTALLED else InstallStatus.UPDATED
        }

        is ThemeResource -> ImportTheme(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = resource.key,
            name = resource.name,
            description = resource.description,
            documentStyles = protocolMapper.mapToDocumentStyles(resource.documentStyles),
            pageSettings = resource.pageSettings,
            blockStylePresets = protocolMapper.mapToBlockStylePresets(resource.blockStylePresets),
            spacingUnit = resource.spacingUnit,
        ).execute()

        is StencilResource -> ImportStencil(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = resource.key,
            name = resource.name,
            description = resource.description,
            tags = resource.tags,
            content = resource.content,
            parameterSchema = resource.parameterSchema,
            readsTemplateData = resource.readsTemplateData,
        ).execute()

        is AttributeResource -> {
            val bindingCatalog = resource.codeListBinding?.let {
                it.catalogKey?.let(CatalogKey::of) ?: catalogKey
            }
            val bindingSlug = resource.codeListBinding?.key?.let(CodeListKey::of)
            ImportAttribute(
                tenantId = tenantId,
                catalogKey = catalogKey,
                slug = resource.key,
                displayName = resource.name,
                allowedValues = resource.allowedValues,
                codeListCatalogKey = bindingCatalog,
                codeListSlug = bindingSlug,
            ).execute()
        }

        is app.epistola.catalog.protocol.CodeListResource -> ImportCodeList(
            tenantId = tenantId,
            catalogKey = catalogKey,
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
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = resource.key,
            name = resource.name,
            kind = resource.kind,
            // Every variant's binary rode the ZIP as an `AssetResource`
            // installed in this same catalog (asset slug = asset UUID).
            // System (CLASSPATH) fonts are never exported.
            variants = resource.variants.map { entry ->
                val faceBytes = binaries(entry.contentPath())
                    ?: throw IllegalArgumentException("Missing font face content: ${entry.contentPath()}")
                val faceKey = materialiseFontFace(tenantId, catalogKey, resource.key, entry, faceBytes)
                app.epistola.suite.fonts.commands.ImportFontVariant(
                    weight = entry.weight,
                    italic = entry.italic,
                    source = app.epistola.suite.fonts.model.FontVariantSource.ASSET,
                    assetKey = faceKey,
                )
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
                tenantId = tenantId,
                catalogKey = catalogKey,
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
    catalogKey: CatalogKey,
    version: String,
    protocolMapper: ProtocolMapper,
): ImportTemplateInput = ImportTemplateInput(
    slug = resource.key,
    name = resource.name,
    version = version,
    themeId = resource.themeKey,
    themeCatalogKey = if (resource.themeKey != null) resource.themeCatalogKey ?: catalogKey.value else null,
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
