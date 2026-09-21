// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.commands

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.identity.requireAddressAvailable
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** One face of a family being authored: its binary, and where it sits in the family. */
class NewFontFace(
    val weight: Int,
    val italic: Boolean,
    val filename: String,
    val mediaType: AssetMediaType,
    val content: ByteArray,
)

/**
 * Creates a font family from uploaded faces -- the authoring path, where [ImportFont] is the
 * catalog-import one.
 *
 * The difference is the reserved address. Import reproduces stored state faithfully, including a
 * family that predates an alias; authoring must not take an address a relocated family still
 * answers to, or every published reference to that address silently starts meaning the new family.
 * The check runs before any face is uploaded, so a refused family leaves no stray binaries, and the
 * whole command is one transaction.
 */
data class CreateFontFamily(
    val tenantId: TenantId,
    val catalogKey: CatalogKey,
    val slug: FontKey,
    val name: String,
    val kind: FontKind,
    val faces: List<NewFontFace>,
) : Command<InstallStatus>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey: TenantKey get() = tenantId.key

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        validate("faces", faces.isNotEmpty()) { "At least one face file is required" }
    }
}

@Component
class CreateFontFamilyHandler(
    private val jdbi: Jdbi,
) : CommandHandler<CreateFontFamily, InstallStatus> {
    override fun handle(command: CreateFontFamily): InstallStatus {
        jdbi.useHandle<Exception> { handle ->
            requireAddressAvailable(
                handle,
                command.tenantKey,
                ResourceAddress(CatalogResourceType.FONT, command.catalogKey.value, command.slug.value),
            )
        }
        val variants = command.faces.map { face ->
            val asset = UploadAsset(
                tenantId = command.tenantKey,
                name = face.filename,
                mediaType = face.mediaType,
                content = face.content,
                width = null,
                height = null,
                catalogKey = command.catalogKey,
            ).execute()
            ImportFontVariant(face.weight, face.italic, FontVariantSource.ASSET, assetKey = asset.id)
        }
        return ImportFont(
            tenantId = command.tenantId,
            catalogKey = command.catalogKey,
            slug = command.slug.value,
            name = command.name,
            kind = command.kind.wire,
            variants = variants,
        ).execute()
    }
}
