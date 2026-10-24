// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
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
 * Makes a transaction work on one catalog version (contract ADR 0002).
 *
 * Content rows belong to a version, and every statement names the one it means through `versionAt`,
 * `inCurrentVersion` or `catalog_current_versions` (`CatalogSql.kt`). Those answer the catalog's
 * current version unless the transaction targets another: [within] sets the PostgreSQL setting
 * `epistola.content_version` to its `version_id`, transaction-locally, and the view prefers that
 * version for its own catalog. So handlers written for "the" version of a catalog work unchanged on
 * any draft, and messages they dispatch inherit the target. The setting is restored when the message
 * ends, so a nested message naming another version does not leak its target into its caller.
 *
 * A [SelfManagedTransaction][app.epistola.suite.mediator.SelfManagedTransaction] handler opens its own
 * transactions; it calls [apply] in each one to take the target its message named.
 */
@Component
class CatalogReleaseTargeting(
    private val jdbi: Jdbi,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * The `version_id` of the draft [ref] names.
     *
     * @throws CatalogNotFoundException when the tenant has no such catalog.
     * @throws CatalogReleaseNotFoundException when the catalog has no such draft: no draft with that
     *   id, or no next version (a subscribed catalog, or one whose next version was discarded).
     */
    fun resolve(ref: CatalogReleaseRef): UUID = jdbi.withHandle<UUID, Exception> { handle ->
        val match = when (val draft = ref.draft) {
            DraftRef.Next -> "v.is_next"

            is DraftRef.Id -> "v.version_key = :id"

            // A release has no draft rows; reading one through the draft queries comes per type (ADR 0033).
            is DraftRef.Released -> throw CatalogReleaseNotFoundException(ref.catalogKey, draft.version)
        }
        handle.createQuery(
            """
            SELECT v.version_id FROM catalog_versions v
            WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.kind = 'draft' AND $match
            """,
        )
            .bind("t", ref.tenantKey)
            .bindCatalog("c", ref.catalog)
            .also { query -> (ref.draft as? DraftRef.Id)?.let { query.bind("id", it.key) } }
            .mapTo(UUID::class.java)
            .findOne()
            .orElseGet {
                if (!catalogExists(handle, ref)) throw CatalogNotFoundException(ref.catalogKey)
                throw CatalogReleaseNotFoundException(ref.catalogKey, ref.draft.toString())
            }
    }

    /**
     * Runs [block] with [versionId] as its catalog's version. Inside a transaction the setting is
     * applied there; outside one (a self-managed command) only [current] carries it, for [apply].
     */
    fun <R> within(versionId: UUID, block: () -> R): R = ScopedValue.where(TARGET, versionId).call<R, RuntimeException> {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return@call block()
        val previous = jdbi.withHandle<String?, Exception> { handle ->
            handle.createQuery("SELECT current_setting('$SETTING', TRUE)").mapTo(String::class.java).findOne().orElse(null)
        }
        jdbi.useHandle<Exception> { set(it, versionId.toString()) }
        try {
            block()
        } finally {
            try {
                jdbi.useHandle<Exception> { set(it, previous.orEmpty()) }
            } catch (e: Exception) {
                // The transaction failed and will roll back, which reverts the setting with it.
                logger.debug("Could not restore {} after a failed message: {}", SETTING, e.message)
            }
        }
    }

    /** Applies the target the running message named, if any, to [handle]'s transaction. */
    fun apply(handle: Handle) {
        current()?.let { set(handle, it.toString()) }
    }

    private fun set(handle: Handle, value: String) {
        handle.createQuery("SELECT set_config('$SETTING', :value, TRUE)").bind("value", value).mapTo(String::class.java).one()
    }

    private fun catalogExists(handle: Handle, ref: CatalogReleaseRef): Boolean = handle
        .createQuery("SELECT EXISTS (SELECT 1 FROM catalogs WHERE tenant_key = :t AND catalog_id = epistola_catalog_id(:t, :c_ns, :c))")
        .bind("t", ref.tenantKey)
        .bindCatalog("c", ref.catalog)
        .mapTo(Boolean::class.java)
        .one()

    companion object {
        /** The PostgreSQL setting `catalog_current_versions` reads. */
        const val SETTING: String = "epistola.content_version"

        private val TARGET: ScopedValue<UUID> = ScopedValue.newInstance()

        /** The `version_id` the running message targets, or null when it targets none. */
        fun current(): UUID? = if (TARGET.isBound) TARGET.get() else null
    }
}
