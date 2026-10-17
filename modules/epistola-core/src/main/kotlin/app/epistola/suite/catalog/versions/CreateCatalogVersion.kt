// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.commands.CatalogReleaseNotRetainedException
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Opens a draft numbered [version] of an authored catalog (contract `createCatalogVersion`, ADR 0002).
 *
 * - Without [from], the draft starts as a copy of its **base**: the highest released version below its
 *   number, or empty when there is none.
 * - With [from], it starts as a copy of that version, a draft or a released one, below or above the new
 *   number. Its base is [from] when that is released, and [from]'s base when it is a draft.
 *
 * A copy keeps every resource's identity. Refused when [version] is in use (drafts and releases), for
 * a subscribed catalog, and when the version to copy is a release that kept no content.
 */
data class CreateCatalogVersion(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val from: String? = null,
) : Command<CatalogVersion>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_PUBLISH

    init {
        validate("version", SemVer.parseOrNull(version) != null, ValidationCode.GENERIC) { "Version '$version' must be MAJOR.MINOR.PATCH" }
        validate("from", from == null || SemVer.parseOrNull(from) != null, ValidationCode.GENERIC) { "Version '$from' must be MAJOR.MINOR.PATCH" }
    }
}

/**
 * Discards a draft and everything changed in it (contract `deleteCatalogVersion`): its content rows,
 * ready marks and dependency declarations. A resource keeps its identity while another version holds
 * it. Refused for a released version. The contract does not keep a catalog's last draft: one without
 * drafts gets one again by [CreateCatalogVersion].
 */
data class DeleteCatalogVersion(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
) : Command<Unit>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_PUBLISH
}

@Component
class CreateCatalogVersionHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val versions: CatalogVersionReader,
    private val targeting: CatalogVersionTargeting,
) : CommandHandler<CreateCatalogVersion, CatalogVersion> {
    override fun handle(command: CreateCatalogVersion): CatalogVersion {
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        val (tenantKey, catalogKey) = command.tenantKey to command.catalogKey
        val all = jdbi.withHandle<List<CatalogVersion>, Exception> { versions.all(it, tenantKey, catalogKey) }
        if (all.any { it.version == command.version }) throw CatalogVersionInUseException(catalogKey, command.version)
        val releases = all.filter { it.status == CatalogVersionStatus.RELEASED }

        val source: CatalogVersion? = when (command.from) {
            null -> CatalogDrafts.highestBelow(command.version, releases.map { it.version })?.let { base -> releases.first { it.version == base } }
            else -> all.firstOrNull { it.version == command.from } ?: throw CatalogVersionNotFoundException(catalogKey, command.from)
        }
        val base = when (source?.status) {
            null -> null
            CatalogVersionStatus.RELEASED -> source.version
            CatalogVersionStatus.DRAFT -> source.base
        }
        // The contract records the source only when it is not the base.
        val createdFrom = command.from?.takeIf { it != base }

        val created = jdbi.withHandle<java.util.UUID, Exception> { drafts.open(it, tenantKey, catalogKey, command.version, base, createdFrom) }
        when (source?.status) {
            null -> {}

            CatalogVersionStatus.DRAFT -> jdbi.useHandle<Exception> { handle ->
                val from = requireNotNull(drafts.named(handle, tenantKey, catalogKey, source.version))
                drafts.copyContent(handle, tenantKey, from.versionId, created)
            }

            CatalogVersionStatus.RELEASED -> copyRelease(command, source, created)
        }
        return jdbi.withHandle<CatalogVersion, Exception> { handle -> versions.all(handle, tenantKey, catalogKey).first { it.version == command.version } }
    }

    /**
     * A released version's content is the content it retained: rebuilt as its archive and imported,
     * as a ZIP import would, into the new draft (the import writes the version the transaction
     * targets). Identities are adopted by address, so a resource another version still holds keeps its
     * identity; one no version holds any more gets a new one.
     */
    private fun copyRelease(command: CreateCatalogVersion, release: CatalogVersion, into: java.util.UUID) {
        if (release.contentRetained != true) throw CatalogReleaseNotRetainedException(command.catalogKey, release.version)
        val archive = ExportCatalogZip(command.tenantKey, command.catalogKey, release.version).execute()
        val imported = targeting.within(into) {
            ImportCatalogZip(
                tenantKey = command.tenantKey,
                zipBytes = archive.zipBytes,
                catalogType = CatalogType.AUTHORED,
                validateCrossCatalogDeps = false,
                confirmMigration = true,
                intoDraft = true,
            ).execute()
        }
        val failed = imported.results.filter { it.status == InstallStatus.FAILED }
        check(failed.isEmpty()) {
            "Copying release ${release.version} of '${command.catalogKey.value}' into draft ${command.version} failed for " +
                failed.joinToString { "${it.type} '${it.slug}': ${it.errorMessage}" }
        }
    }
}

@Component
class DeleteCatalogVersionHandler(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val versions: CatalogVersionReader,
) : CommandHandler<DeleteCatalogVersion, Unit> {
    override fun handle(command: DeleteCatalogVersion) {
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        jdbi.useHandle<Exception> { handle ->
            val draft = drafts.named(handle, command.tenantKey, command.catalogKey, command.version)
            if (draft == null) {
                val released = versions.all(handle, command.tenantKey, command.catalogKey).any { it.version == command.version }
                if (released) throw CatalogVersionReleasedException(command.catalogKey, command.version)
                throw CatalogVersionNotFoundException(command.catalogKey, command.version)
            }
            drafts.delete(handle, command.tenantKey, draft.versionId)
        }
    }
}
