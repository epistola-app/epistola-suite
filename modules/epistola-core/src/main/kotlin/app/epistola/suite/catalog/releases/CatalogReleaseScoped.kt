// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey

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

    /**
     * A version already resolved, by its internal `version_id` (ADR 0049): what internal work that
     * holds a version (an import into a draft, a merge, a release) passes on to the messages it
     * dispatches. Never written in an address, so never parsed.
     */
    data class At(val versionId: java.util.UUID) : DraftRef {
        override fun toString(): String = versionId.toString()
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
 * A command or query that addresses one version of a catalog (contract ADR 0002: every resource path
 * of a draft is `…/versions/{catalogVersion}/…`), as it addresses a tenant (ADR 0049).
 *
 * Its handler resolves [catalogVersion] through [ContentVersions] and binds the `version_id` it
 * answers to the statements it runs. When it is null the message means its catalog's next version.
 * Nothing is inherited: a handler that dispatches another message for the same version passes it on.
 */
interface CatalogReleaseScoped {
    /** The version this message addresses, or null for its catalog's next version. */
    val catalogVersion: CatalogReleaseRef?
}

/**
 * A query that can read a released version as well as a draft (ADR 0033). A message that is not this,
 * and names a release, is refused ([CatalogReleaseNotFoundException]) by [ContentVersions].
 */
interface ReleaseReadable : CatalogReleaseScoped
