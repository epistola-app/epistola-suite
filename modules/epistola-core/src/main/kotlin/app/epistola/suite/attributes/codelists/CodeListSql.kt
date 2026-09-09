// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists

/**
 * The identity of the code list at a `(catalogKey, slug)` address.
 *
 * Entries and attribute bindings reference a code list by `resource_id`, so moving or renaming one
 * needs no cascade and rewrites nothing. Callers still speak addresses — that is what a `CodeListId`
 * is — so the address is turned into an identity here, at the query boundary, rather than being
 * stored a second time on every dependant row.
 *
 * Expects `:tenantKey`, `:catalogKey` and `:slug` to be bound by the surrounding statement.
 */
internal const val CODE_LIST_AT_ADDRESS: String =
    "(SELECT resource_id FROM code_lists WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND slug = :slug)"

/**
 * The identity of the code list a caller named by address, for writing a binding.
 *
 * An address that resolves to nothing yields a sentinel rather than NULL, so the foreign key
 * rejects the write. Resolving to NULL instead would silently store "unbound" for a binding the
 * caller explicitly asked for — the failure mode the composite foreign key used to make impossible.
 * A genuine absence still binds NULL, which is what an unbound attribute stores.
 *
 * @param tenantParam name of the bound tenant parameter, which differs between call sites.
 *   Expects `:codeListCatalogKey` and `:codeListSlug` alongside it.
 */
internal fun boundCodeListAtAddress(tenantParam: String): String =
    """
    -- Cast so the parameter has a type here; the comparison below infers one from the column.
    CASE WHEN CAST(:codeListSlug AS TEXT) IS NULL THEN NULL
         ELSE COALESCE(
             (SELECT resource_id FROM code_lists
               WHERE tenant_key = :$tenantParam
                 AND catalog_key = :codeListCatalogKey
                 AND slug = :codeListSlug),
             '00000000-0000-0000-0000-000000000000'::uuid)
    END
    """
