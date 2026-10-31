// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * A write never leaves a version naming something it did not name before and cannot resolve: a
 * stencil nobody has, a catalog the tenant lacks, a pin on a release without the resource used. What
 * already dangled stays, so a draft upgraded from 1.x with such a reference can still be edited; only
 * releasing it refuses it.
 *
 * Every writer of a version passes the freeze guard (`requireWritableVersion`), which reports the
 * version here. Its first write in a transaction notes what of it is unresolved then; before the
 * transaction commits, anything unresolved that was not is refused, and the transaction rolls back.
 * Checking at commit, not per write, lets one command write a template before the stencil it uses.
 * Outside a Spring transaction (a migration) nothing is checked.
 */
object DraftReferenceGuard {
    private data class Version(val tenantKey: TenantKey, val versionId: UUID)

    private class Touched(val jdbi: Jdbi) : TransactionSynchronization {
        val before = LinkedHashMap<Version, Set<DraftResources.Unresolved>>()

        override fun beforeCommit(readOnly: Boolean) {
            val added = jdbi.withHandle<Map<Version, Set<DraftResources.Unresolved>>, Exception> { handle ->
                before.mapValues { (version, was) -> DraftResources.unresolved(handle, version.tenantKey, version.versionId) - was }
            }.values.flatten()
            if (added.isNotEmpty()) {
                throw ValidationException(
                    "references",
                    "This would use what nothing here has: ${added.joinToString("; ")}. Add it, or declare the catalog that has it, first.",
                    ValidationCode.CATALOG_REFERENCE_UNRESOLVED,
                )
            }
        }

        override fun afterCompletion(status: Int) {
            TransactionSynchronizationManager.unbindResourceIfPossible(KEY)
        }
    }

    private val KEY = Any()

    /** [versionId] of [tenantKey] is about to be written on [handle]'s transaction. */
    fun writing(handle: Handle, tenantKey: TenantKey, versionId: UUID) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        val touched = TransactionSynchronizationManager.getResource(KEY) as Touched?
            ?: Touched(handle.jdbi).also {
                TransactionSynchronizationManager.bindResource(KEY, it)
                TransactionSynchronizationManager.registerSynchronization(it)
            }
        val version = Version(tenantKey, versionId)
        if (version !in touched.before) touched.before[version] = DraftResources.unresolved(handle, tenantKey, versionId)
    }
}
