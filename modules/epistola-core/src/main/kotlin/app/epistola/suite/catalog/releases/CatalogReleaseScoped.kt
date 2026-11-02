// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.isNext
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.qualifier.QualifiedType
import org.jdbi.v3.core.statement.SqlStatement
import org.jdbi.v3.core.statement.StatementContext
import org.jdbi.v3.core.statement.StatementCustomizer
import org.springframework.stereotype.Component
import java.sql.PreparedStatement
import java.util.UUID

/**
 * A version of a catalog as a message names it (contract ADR 0002; ADR 0033): its next version (a
 * convention of this suite), a draft by its id, or a release by its number ([Released]). A draft has
 * no number until it is released, so a number never names a draft, and a version id never has a dot,
 * so the two never collide. A release is read-only: no command may target one.
 */
sealed interface DraftRef {
    /** The catalog's next version: the draft everyday work goes into. */
    data object Next : DraftRef {
        override fun toString(): String = NEXT
    }

    /** A draft by its public id (contract `CatalogVersionId`), its `version_key`. */
    data class Id(val key: String) : DraftRef {
        override fun toString(): String = key
    }

    /**
     * A released version by its number (`1.2.0`), read-only (ADR 0033): the write guard refuses every
     * command that targets one ([ReleasedVersionReadOnlyException]). Reads of a release through the
     * draft queries do not switch to it yet; each resource type does in its own step.
     */
    data class Released(val version: String) : DraftRef {
        override fun toString(): String = version
    }

    companion object {
        /** How [Next] is written in an address. */
        const val NEXT: String = "next"

        /** A version id: lowercase letters, digits and single hyphens, 4 to 36 characters, never a dot. */
        private val KEY = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

        /** Whether [value] has the shape of a version id. */
        fun isKey(value: String): Boolean = value.length in 4..36 && KEY.matches(value)

        /** [value] as an address writes it: `next`, a version id or a release number; null when it is none. */
        fun parse(value: String): DraftRef? = when {
            value == NEXT -> Next
            isKey(value) -> Id(value)
            app.epistola.catalog.protocol.CatalogReleaseVersions.isValid(value) -> Released(value)
            else -> null
        }

        /** [value] as a draft: `next` or a version id; null for a release number or anything else. */
        fun parseDraft(value: String): DraftRef? = parse(value)?.takeUnless { it is Released }
    }
}

/** One draft of a catalog, as a message names it. */
data class CatalogReleaseRef(
    val catalog: CatalogId,
    val draft: DraftRef,
) {
    val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
}

/**
 * A command or query that can address one version of a catalog (contract ADR 0002: every resource
 * path of a draft is `…/versions/{catalogVersion}/…`).
 *
 * When [catalogVersion] names one, `SpringMediator` resolves it and runs the message, and every
 * message it dispatches in turn, with that version as its catalog's version: the SQL helpers
 * `versionAt` and `inCurrentVersion` and the view `catalog_current_versions` answer it instead of the
 * catalog's current version (see [CatalogReleaseTargeting]). When it is null, the message works on
 * the current version, exactly as one that does not implement this does.
 */
interface CatalogReleaseScoped {
    /** The version this message addresses, or null for its catalog's current version. */
    val catalogVersion: CatalogReleaseRef?
}

/**
 * A query that can read a released version as well as a draft (ADR 0033): its SQL reads through
 * `catalog_version_resources(type, …)` (or a read on it, such as `current_themes(…)`), which answers the rows
 * of the release a message targets. A message that is not this, and names a release, finds nothing
 * there ([CatalogReleaseNotFoundException]), until its resource type moves.
 */
interface ReleaseReadable : CatalogReleaseScoped

/**
 * Makes a message work on one catalog version (contract ADR 0002).
 *
 * Content rows belong to a version, and every statement names the one it means through `versionAt`,
 * `inCurrentVersion` or a function taking [VERSION_ARGS][app.epistola.suite.catalog.VERSION_ARGS]
 * (`CatalogSql.kt`). Those answer the catalog's current version unless the message targets another:
 * [within] and [withinRelease] hold the target for their block, and [VersionTargetArguments] passes
 * it to every statement that runs inside it, as arguments. The schema reads nothing from the session
 * (ADR 0049). So handlers written for "the" version of a catalog work unchanged on any draft, messages
 * they dispatch inherit the target, a nested message naming another version does not leak its target
 * into its caller, and a handler that opens its own transactions
 * ([SelfManagedTransaction][app.epistola.suite.mediator.SelfManagedTransaction]) keeps it in each.
 */
@Component
class CatalogReleaseTargeting(
    private val jdbi: Jdbi,
) {
    /**
     * The `version_id` of the draft [ref] names.
     *
     * @throws CatalogNotFoundException when the tenant has no such catalog.
     * @throws CatalogReleaseNotFoundException when the catalog has no such draft: no draft with that
     *   id, or no next version (a subscribed catalog, or one whose next version was discarded).
     */
    fun resolve(ref: CatalogReleaseRef, write: Boolean = false): UUID = jdbi.withHandle<UUID, Exception> { handle ->
        val match = when (val draft = ref.draft) {
            DraftRef.Next -> isNext("v")

            is DraftRef.Id -> "v.version_key = :id"

            // A release has no draft rows; reading one through the draft queries comes per type (ADR 0033).
            is DraftRef.Released -> throw CatalogReleaseNotFoundException(ref.catalogKey, draft.version)
        }
        handle.createQuery(
            """
            SELECT v.version_id FROM catalog_versions v
            WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.state = 'draft' AND $match
            """,
        )
            .bind("t", ref.tenantKey)
            .bindCatalog("c", ref.catalog)
            .also { query -> (ref.draft as? DraftRef.Id)?.let { query.bind("id", it.key) } }
            .mapTo(UUID::class.java)
            .findOne()
            .orElseGet {
                if (!catalogExists(handle, ref)) throw CatalogNotFoundException(ref.catalogKey)
                // A draft that was released keeps its id as the release (ADR 0036): a change made
                // through that id is refused as such, so its author opens the next draft.
                if (write) releasedAs(handle, ref)?.let { throw CatalogReleaseIsReleasedException(ref.catalogKey, it) }
                throw CatalogReleaseNotFoundException(ref.catalogKey, ref.draft.toString())
            }
    }

    /** The release number the draft [ref] names by its id became, or null. */
    private fun releasedAs(handle: Handle, ref: CatalogReleaseRef): String? {
        val id = (ref.draft as? DraftRef.Id)?.key ?: return null
        return handle.createQuery(
            "SELECT v.version FROM catalog_versions v WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.state = 'released' AND v.version_key = :id",
        )
            .bind("t", ref.tenantKey)
            .bindCatalog("c", ref.catalog)
            .bind("id", id)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
    }

    /** Runs [block] with [versionId] as its catalog's version, and every statement inside it reading it. */
    fun <R> within(versionId: UUID, block: () -> R): R = withDraft(versionId, block)

    /**
     * The `catalog_id` of the release [ref] names, for [withinRelease].
     *
     * @throws CatalogNotFoundException when the tenant has no such catalog.
     * @throws CatalogReleaseNotFoundException when the catalog has no such release.
     */
    fun resolveRelease(ref: CatalogReleaseRef): UUID = jdbi.withHandle<UUID, Exception> { handle ->
        val version = (ref.draft as DraftRef.Released).version
        handle.createQuery(
            "SELECT r.catalog_id FROM catalog_versions r WHERE r.state = 'released' AND r.tenant_key = :t AND r.catalog_id = ${catalogAt("t", "c")} AND r.version = :v",
        )
            .bind("t", ref.tenantKey)
            .bindCatalog("c", ref.catalog)
            .bind("v", version)
            .mapTo(UUID::class.java)
            .findOne()
            .orElseGet {
                if (!catalogExists(handle, ref)) throw CatalogNotFoundException(ref.catalogKey)
                throw CatalogReleaseNotFoundException(ref.catalogKey, version)
            }
    }

    /** Runs [block] reading release [version] of the catalog [catalogId] (ADR 0033). */
    fun <R> withinRelease(catalogId: UUID, version: String, block: () -> R): R = withRelease(catalogId, version, block)

    private fun catalogExists(handle: Handle, ref: CatalogReleaseRef): Boolean = handle
        .createQuery("SELECT EXISTS (SELECT 1 FROM catalogs WHERE tenant_key = :t AND catalog_id = epistola_catalog_id(:t, :c_ns, :c))")
        .bind("t", ref.tenantKey)
        .bindCatalog("c", ref.catalog)
        .mapTo(Boolean::class.java)
        .one()

    /**
     * Passes the target in scope to the statement that runs, as the named parameters of
     * [VERSION_ARGS][app.epistola.suite.catalog.VERSION_ARGS], when it names them. Bound when the
     * statement runs rather than when its SQL is built, so a statement built once reads the version of
     * the message running it. Installed on the application's [Jdbi] (`JdbiConfig`).
     */
    object VersionTargetArguments : StatementCustomizer {
        const val DRAFT_VERSION: String = "epistolaDraftVersion"
        const val RELEASE_CATALOG: String = "epistolaReleaseCatalog"
        const val RELEASE_VERSION: String = "epistolaReleaseVersion"

        private val UUID_TYPE: QualifiedType<UUID> = QualifiedType.of(UUID::class.java)
        private val TEXT_TYPE: QualifiedType<String> = QualifiedType.of(String::class.java)

        override fun beforeBinding(stmt: PreparedStatement, ctx: StatementContext) {
            val names = ctx.parsedSql?.parameters?.parameterNames ?: return
            if (DRAFT_VERSION !in names && RELEASE_CATALOG !in names && RELEASE_VERSION !in names) return
            val release = currentRelease()
            if (DRAFT_VERSION in names) ctx.binding.addNamed(DRAFT_VERSION, current(), UUID_TYPE)
            if (RELEASE_CATALOG in names) ctx.binding.addNamed(RELEASE_CATALOG, release?.first, UUID_TYPE)
            if (RELEASE_VERSION in names) ctx.binding.addNamed(RELEASE_VERSION, release?.second, TEXT_TYPE)
        }
    }

    companion object {
        private val TARGET: ScopedValue<UUID?> = ScopedValue.newInstance()

        private val RELEASE_TARGET: ScopedValue<Pair<UUID, String>?> = ScopedValue.newInstance()

        /** The `version_id` of the draft the running message targets, or null when it targets none. */
        fun current(): UUID? = if (TARGET.isBound) TARGET.get() else null

        /** The release (`catalog_id` and number) the running message reads, or null when it reads none. */
        fun currentRelease(): Pair<UUID, String>? = if (RELEASE_TARGET.isBound) RELEASE_TARGET.get() else null

        /**
         * Runs [block] with draft [versionId] as its catalog's version, and no release read: what
         * [within] and a draft [VersionReads] selection mean.
         */
        internal fun <R> withDraft(versionId: UUID, block: () -> R): R = ScopedValue.where(TARGET, versionId).where(RELEASE_TARGET, null).call<R, RuntimeException>(block)

        /**
         * Runs [block] reading release [version] of [catalogId], or no release when [catalogId] is
         * null; the draft targeted around it stays. What [withinRelease] and a release [VersionReads]
         * selection mean.
         */
        internal fun <R> withRelease(catalogId: UUID?, version: String, block: () -> R): R = ScopedValue.where(RELEASE_TARGET, catalogId?.let { it to version }).call<R, RuntimeException>(block)
    }
}

/**
 * Binds the target in scope to [VERSION_ARGS][app.epistola.suite.catalog.VERSION_ARGS] on this
 * statement, as [CatalogReleaseTargeting.VersionTargetArguments] does when a statement runs. For the
 * parts of a [org.jdbi.v3.core.statement.PreparedBatch]: each part has a binding of its own, which a
 * statement customizer cannot reach, so a batch that names `VERSION_ARGS` calls this before each `add()`.
 */
fun <T : SqlStatement<T>> T.bindVersionTarget(): T {
    val release = CatalogReleaseTargeting.currentRelease()
    return bindByType(CatalogReleaseTargeting.VersionTargetArguments.DRAFT_VERSION, CatalogReleaseTargeting.current(), UUID::class.java)
        .bindByType(CatalogReleaseTargeting.VersionTargetArguments.RELEASE_CATALOG, release?.first, UUID::class.java)
        .bindByType(CatalogReleaseTargeting.VersionTargetArguments.RELEASE_VERSION, release?.second, String::class.java)
}
