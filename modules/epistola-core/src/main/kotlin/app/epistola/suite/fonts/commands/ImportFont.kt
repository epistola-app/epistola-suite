// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.commands

import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.FontStore
import app.epistola.suite.fonts.model.FontFace
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * One face carried by [ImportFont], keyed by CSS numeric [weight] (1–1000; 400 = regular,
 * 700 = bold) and [italic], with its file: [content], a static TTF or OTF ([mediaType]). The file is
 * the font's own binary (ADR 0033); [contentHash] is what it is stored under.
 */
class ImportFontVariant(
    val weight: Int,
    val italic: Boolean,
    val mediaType: String,
    val content: ByteArray,
    val contentHash: String = FontStore.hashOf(content),
) {
    /** The face as the font's content names it. */
    fun face(): FontFace = FontFace(weight, italic, mediaType, contentHash)

    override fun equals(other: Any?): Boolean = other is ImportFontVariant && other.face() == face()

    override fun hashCode(): Int = face().hashCode()

    override fun toString(): String = "ImportFontVariant(${face()})"
}

/**
 * Creates or replaces a font family of a draft, with its faces' files ([FontStore]): what an
 * upload, a catalog install and the system font seeding all do. Matches the `ImportCodeList` shape
 * used by `InstallFromCatalog` / `ImportCatalogZip`.
 *
 * `kind` arrives as the lowercase wire string (`sans` / `serif` / …). Re-running it replaces the
 * faces at once; with the same faces it writes nothing.
 */
data class ImportFont(
    val catalog: CatalogId,
    val slug: String,
    val name: String,
    val kind: String,
    val variants: List<ImportFontVariant> = emptyList(),
    /** Producer metadata (contract `CatalogResource.metadata`), as received; null keeps what the draft holds. */
    val metadata: app.epistola.suite.catalog.revisions.ProducerMetadata? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Command<InstallStatus>,
    RequiresPermission {
    val tenantId: TenantId get() = catalog.tenantId
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey: TenantKey get() = tenantId.key

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        variants.forEach { face ->
            val label = "Face ${face.weight}${if (face.italic) " italic" else ""}"
            validate("variants", face.weight in 1..1000) { "$label: weight must be between 1 and 1000" }
            validate("variants", face.mediaType in FONT_MEDIA_TYPES) { "$label: a face is a TTF or OTF file" }
            // As large as an uploaded asset may be; a face used to be one.
            validate("variants", face.content.size <= app.epistola.suite.assets.MAX_ASSET_SIZE_BYTES) { "$label: a face is at most 5 MB" }
        }
        validate("variants", variants.map { it.weight to it.italic }.toSet().size == variants.size) { "Each weight/italic face may appear only once" }
    }

    companion object {
        /** The files a face may be. */
        val FONT_MEDIA_TYPES: Set<String> = setOf("font/ttf", "font/otf")
    }
}

@Component
class ImportFontHandler(
    private val jdbi: Jdbi,
    private val fonts: FontStore,
) : CommandHandler<ImportFont, InstallStatus> {

    override fun handle(command: ImportFont): InstallStatus {
        // Catalog install and upgrade write fonts into catalogs that cannot be changed inside
        // CatalogImportContext, which the guard lets through; a user upload must not.
        requireCatalogEditable(command.catalog)
        return jdbi.inTransaction<InstallStatus, Exception> { handle ->
            fonts.put(handle, command.catalog, command.slug, command.name, command.kind, command.variants, currentUserIdOrNull()?.value, command.metadata)
        }
    }
}
