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
 * stay. Refused for a released version and a number in use. A renumber with warnings needs
 * [acknowledgeWarnings].
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
    override val permission get() = Permission.TEMPLATE_EDIT

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
        val from = SemVer.parse(version)
        val target = SemVer.parse(to)
        val base = current.base?.let(SemVer::parseOrNull)
        val releases = all.filter { it.status == CatalogVersionStatus.RELEASED }.mapNotNull { r -> SemVer.parseOrNull(r.version)?.let { it to r.version } }
        val warnings = mutableListOf<RenumberWarning>()

        // Up past releases it does not contain: it becomes behind them (ADR 0002 `falls-behind`).
        val behindNow = CatalogDrafts.behind(version, current.base, releases.map { it.second }).toSet()
        val fallsBehind = CatalogDrafts.behind(to, current.base, releases.map { it.second }).filter { it !in behindNow }
        if (fallsBehind.isNotEmpty()) {
            warnings += RenumberWarning(
                "falls-behind",
                "This draft does not contain release ${fallsBehind.joinToString()}; as $to it will be behind ${if (fallsBehind.size == 1) "it" else "them"}.",
                fallsBehind,
            )
        }
        // Down past releases it contains (its base and what came before it): it will release what
        // they did, under a lower number.
        val containsLater = releases.filter { (semVer, _) -> semVer > target && semVer < from && base != null && semVer <= base }.sortedBy { it.first }.map { it.second }
        if (containsLater.isNotEmpty()) {
            warnings += RenumberWarning(
                "contains-later-releases",
                "This draft contains the changes of release ${containsLater.joinToString()}; as $to it will release them under a lower number.",
                containsLater,
            )
        }
        // Past another open draft: which draft is released first, and which is the current one, changes.
        val low = minOf(from, target)
        val high = maxOf(from, target)
        val passed = all.filter { it.status == CatalogVersionStatus.DRAFT && it.version != version }
            .mapNotNull { d -> SemVer.parseOrNull(d.version)?.let { it to d.version } }
            .filter { (semVer, _) -> semVer > low && semVer < high }
            .sortedBy { it.first }
            .map { it.second }
        if (passed.isNotEmpty()) {
            warnings += RenumberWarning(
                "passes-draft",
                "As $to this draft moves past draft ${passed.joinToString()}; the order the drafts are released and numbered in changes.",
                passed,
            )
        }
        return warnings
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
            drafts.renumber(handle, command.tenantKey, command.catalogKey, command.version, command.to)
            versions.all(handle, command.tenantKey, command.catalogKey).first { it.version == command.to }
        }
    }
}
