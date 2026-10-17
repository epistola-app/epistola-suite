// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** A reason to look twice at a renumber (contract `RenumberWarningDto`); [kind] is an open set. */
data class RenumberWarning(val kind: String, val message: String, val versions: List<String> = emptyList())

/** What renumbering [version] to [to] would warn about, without doing it (contract `previewCatalogVersionRenumber`). */
data class PreviewCatalogVersionRenumber(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val to: String,
) : Query<List<RenumberWarning>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/**
 * Gives a draft another version number (contract `renumberCatalogVersion`); its content and base
 * stay. Refused for a released version, a number in use, and, while a catalog has one draft, a
 * number not above every release. A renumber with warnings needs [acknowledgeWarnings].
 *
 * @return the version under its new number.
 */
data class RenumberCatalogVersion(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val to: String,
    val acknowledgeWarnings: Boolean = false,
) : Command<CatalogVersion>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_PUBLISH

    init {
        validate("version", SemVer.parseOrNull(to) != null, ValidationCode.GENERIC) { "Version '$to' must be MAJOR.MINOR.PATCH" }
    }
}

/** Thrown when a renumber has warnings that were not acknowledged. */
class RenumberWarningsNotAcknowledgedException(val warnings: List<RenumberWarning>) : RuntimeException("Renumbering needs acknowledging: " + warnings.joinToString("; ") { it.message })

@Component
class RenumberChecks(
    private val versions: CatalogVersionReader,
) {
    /** The warnings of renumbering [version] to [to]; throws for what is refused outright. */
    fun check(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String, to: String): List<RenumberWarning> {
        val all = versions.all(handle, tenantKey, catalogKey)
        val current = all.firstOrNull { it.version == version } ?: throw CatalogVersionNotFoundException(catalogKey, version)
        if (current.status == CatalogVersionStatus.RELEASED) throw CatalogVersionReleasedException(catalogKey, version)
        if (to == version) return emptyList()
        if (all.any { it.version == to }) throw CatalogVersionInUseException(catalogKey, to)
        val target = SemVer.parse(to)
        val highestRelease = all.filter { it.status == CatalogVersionStatus.RELEASED }.mapNotNull { SemVer.parseOrNull(it.version) }.maxOrNull()
        if (highestRelease != null && target <= highestRelease) {
            throw SingleCatalogDraftException(
                catalogKey,
                "Version $to is not above $highestRelease, the latest release of '${catalogKey.value}': its one draft is released after every release",
            )
        }
        // With one draft, based on the latest release, nothing can be released between its base and
        // its number, so no renumber falls behind a release (ADR 0002 `falls-behind`).
        return emptyList()
    }
}

@Component
class PreviewCatalogVersionRenumberHandler(
    private val jdbi: Jdbi,
    private val checks: RenumberChecks,
) : QueryHandler<PreviewCatalogVersionRenumber, List<RenumberWarning>> {
    override fun handle(query: PreviewCatalogVersionRenumber): List<RenumberWarning> = jdbi.withHandle<List<RenumberWarning>, Exception> { handle ->
        checks.check(handle, query.tenantKey, query.catalogKey, query.version, query.to)
    }
}

@Component
class RenumberCatalogVersionHandler(
    private val jdbi: Jdbi,
    private val checks: RenumberChecks,
    private val drafts: CatalogDrafts,
    private val versions: CatalogVersionReader,
) : CommandHandler<RenumberCatalogVersion, CatalogVersion> {
    override fun handle(command: RenumberCatalogVersion): CatalogVersion {
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        return jdbi.inTransaction<CatalogVersion, Exception> { handle ->
            val warnings = checks.check(handle, command.tenantKey, command.catalogKey, command.version, command.to)
            if (warnings.isNotEmpty() && !command.acknowledgeWarnings) throw RenumberWarningsNotAcknowledgedException(warnings)
            drafts.renumber(handle, command.tenantKey, command.catalogKey, command.to)
            versions.all(handle, command.tenantKey, command.catalogKey).first { it.version == command.to }
        }
    }
}
