// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common

/**
 * A write named the revision of the content it started from, and the content has changed since:
 * someone else saved in between. Nothing was written. The caller reads it again and reapplies its
 * change; the editor offers to reload or overwrite.
 *
 * @property current the revision the content is at now.
 */
class ResourceModifiedException(
    val resource: String,
    val expected: Long,
    val current: Long,
) : RuntimeException("$resource was changed by someone else since it was opened. Reload it and apply your change again.")

/**
 * Optimistic locking for editable content (`revision`, raised by one on every update by the
 * `bump_content_revision` trigger).
 *
 * A guarded update adds [MATCHES] to its `WHERE` and binds `expectedRevision` (null: no guard, the
 * write proceeds as last write wins). When it updates nothing, [refuse] tells a stale revision from a
 * row that is not there.
 */
object Revisions {
    /** The update's condition on the row's revision; binds `expectedRevision`. Unqualified `revision`. */
    const val MATCHES: String = "(CAST(:expectedRevision AS BIGINT) IS NULL OR revision = :expectedRevision)"

    /**
     * After a guarded update changed no row: throws [ResourceModifiedException] when the row exists at
     * another revision than [expected] ([current] is its revision, null when there is no row); returns
     * normally when there is no row, so the caller reports it missing as it did before.
     */
    fun refuse(resource: String, expected: Long?, current: Long?) {
        if (expected != null && current != null && current != expected) throw ResourceModifiedException(resource, expected, current)
    }
}
