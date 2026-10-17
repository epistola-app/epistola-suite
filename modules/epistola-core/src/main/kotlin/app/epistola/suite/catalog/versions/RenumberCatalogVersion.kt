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

/**
 * A reason to look twice at a renumber (contract `RenumberWarningDto`); [kind] is an open set.
 * [reasons] say what the warning found, each naming a resource; [text] is the message with them.
 */
data class RenumberWarning(
    val kind: String,
    val message: String,
    val versions: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
) {
    /** The message followed by its reasons, for a reader that shows one string. */
    val text: String get() = if (reasons.isEmpty()) message else "$message " + reasons.joinToString("; ") + "."
}

/** What renumbering [version] to [to] would warn about, without doing it (contract `previewCatalogVersionRenumber`). */
data class PreviewCatalogVersionRenumber(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val to: String,
) : Query<List<RenumberWarning>>,
    CatalogVersionScoped,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW

    /** The draft itself: its content is what its changes are measured on. */
    override val catalogVersion: CatalogVersionRef get() = CatalogVersionRef(tenantKey, catalogKey, version)
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
    CatalogVersionScoped,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_EDIT

    /** The draft itself: its content is what its changes are measured on. */
    override val catalogVersion: CatalogVersionRef get() = CatalogVersionRef(tenantKey, catalogKey, version)

    init {
        validate("version", SemVer.parseOrNull(to) != null, ValidationCode.GENERIC) { "Version '$to' must be MAJOR.MINOR.PATCH" }
    }
}

/** Thrown when a renumber has warnings that were not acknowledged. */
class RenumberWarningsNotAcknowledgedException(val warnings: List<RenumberWarning>) : RuntimeException("Renumbering needs acknowledging: " + warnings.joinToString(" ") { it.text })

@Component
class RenumberChecks(
    private val versions: CatalogVersionReader,
    private val changeKinds: DraftChangeKinds,
) {
    /**
     * The warnings of renumbering [version] to [to]; throws for what is refused outright. The caller
     * targets draft [version] (its message is [CatalogVersionScoped]), so its content is what is read.
     */
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
        // To a lower kind of version than its changes against its base need (ADR 0002, by the rule
        // ADR 0001 sets for hotfixes): breaking needs a major, additive at least a minor.
        val step = base?.let { VersionStep.between(it, target) }
        if (step != null && step != VersionStep.MAJOR) {
            changeKinds.against(tenantKey, catalogKey, requireNotNull(current.base))?.let { changes ->
                lowerKind(step, changes, to, requireNotNull(current.base))?.let { warnings += it }
            }
        }
        return warnings
    }

    private fun lowerKind(step: VersionStep, changes: ChangeAssessment, to: String, base: String): RenumberWarning? {
        if (changes.kind <= step.allows) return null
        val breaking = changes.kind == ChangeKind.BREAKING
        val kind = when {
            breaking && step == VersionStep.PATCH -> "breaking-changes-in-patch"
            breaking -> "breaking-changes-in-minor"
            else -> "additions-in-patch"
        }
        val needs = if (breaking) "a new major version" else "at least a new minor version"
        val what = if (breaking) "breaking" else "additions"
        return RenumberWarning(
            kind,
            "As $to this draft is a ${step.name.lowercase()} of $base, but its changes against $base are $what, which need $needs:",
            listOf(base),
            changes.above(step.allows).map { it.toString() },
        )
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
