// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** One version of a catalog, by number, as a message names it. */
data class CatalogVersionRef(
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
)

/**
 * A command or query that can address one version of a catalog (contract ADR 0002: every resource
 * path of a draft is `…/versions/{catalogVersion}/…`).
 *
 * When [catalogVersion] names one, `SpringMediator` resolves it and runs the message, and every
 * message it dispatches in turn, with that version as its catalog's version: the SQL helpers
 * `versionAt` and `inCurrentVersion` and the view `catalog_current_versions` answer it instead of the
 * catalog's current version (see [CatalogVersionTargeting]). When it is null, the message works on
 * the current version, exactly as one that does not implement this does.
 */
interface CatalogVersionScoped {
    /** The version this message addresses, or null for its catalog's current version. */
    val catalogVersion: CatalogVersionRef?
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
class CatalogVersionTargeting(
    private val jdbi: Jdbi,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * The `version_id` of [ref]. A draft or an installed release has one.
     *
     * @throws CatalogNotFoundException when the tenant has no such catalog.
     * @throws CatalogVersionReleasedException when [ref] names a released version: its content is the
     *   retained release content, read through the release, and never written.
     * @throws CatalogVersionNotFoundException when the catalog has no version with that number.
     */
    fun resolve(ref: CatalogVersionRef): UUID = jdbi.withHandle<UUID, Exception> { handle ->
        handle.createQuery(
            """
            SELECT v.version_id FROM catalog_versions v
            WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.version = :v
            """,
        )
            .bind("t", ref.tenantKey)
            .bind("c", ref.catalogKey)
            .bind("v", ref.version)
            .mapTo(UUID::class.java)
            .findOne()
            .orElseGet {
                val released = handle.createQuery(
                    """
                    SELECT EXISTS (SELECT 1 FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v)
                    """,
                )
                    .bind("t", ref.tenantKey)
                    .bind("c", ref.catalogKey)
                    .bind("v", ref.version)
                    .mapTo(Boolean::class.java)
                    .one()
                when {
                    released -> throw CatalogVersionReleasedException(ref.catalogKey, ref.version)
                    !catalogExists(handle, ref) -> throw CatalogNotFoundException(ref.catalogKey)
                    else -> throw CatalogVersionNotFoundException(ref.catalogKey, ref.version)
                }
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

    private fun catalogExists(handle: Handle, ref: CatalogVersionRef): Boolean = handle
        .createQuery("SELECT EXISTS (SELECT 1 FROM catalogs WHERE tenant_key = :t AND catalog_key = :c)")
        .bind("t", ref.tenantKey)
        .bind("c", ref.catalogKey)
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
