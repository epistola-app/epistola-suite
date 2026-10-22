// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.namespaces.HeldNamespaces
import app.epistola.suite.catalog.namespaces.NamespaceName

/**
 * What a new catalog may be named in (ADR 0034, section 3): the namespaces the tenant holds, and
 * `local` with a namespace of its own, which needs no grant.
 */
data class NamespacesInfo(
    /** The namespaces this tenant holds. */
    val held: List<NamespaceNameInfo>,
    /** What `create_catalog` names a catalog in when it is given no namespace; null when the tenant holds none, or several and none preferred. */
    val default: NamespaceNameInfo?,
    /** The local namespaces this tenant's catalogs are named in already. */
    val localInUse: List<String>,
) {
    companion object {
        fun from(namespaces: HeldNamespaces): NamespacesInfo = NamespacesInfo(
            held = namespaces.held.map(NamespaceNameInfo::from),
            default = namespaces.choice?.let(NamespaceNameInfo::from),
            localInUse = namespaces.localInUse,
        )
    }
}

/** A namespace under its authority, written `authority/namespace`. */
data class NamespaceNameInfo(val authority: String, val namespace: String) {
    companion object {
        fun from(name: NamespaceName): NamespaceNameInfo = NamespaceNameInfo(name.authority, name.namespace)
    }
}
