// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

/**
 * The faces of the family at an address, joined so callers can keep naming a family by where it
 * lives while the rows themselves name the family's identity.
 */
internal const val FACES_OF_FAMILY_AT_ADDRESS: String =
    """
    FROM font_variants faces
    JOIN fonts family ON family.tenant_key = faces.tenant_key AND family.resource_id = faces.font_resource_id
    LEFT JOIN assets binary_asset
           ON binary_asset.tenant_key = faces.tenant_key
          AND binary_asset.resource_id = faces.asset_resource_id
    WHERE family.tenant_key = :tenantKey AND family.catalog_key = :catalogKey AND family.slug = :slug
    """

/**
 * The identity of the asset a caller named by address, for writing a face's binary pointer.
 *
 * An address that resolves to nothing yields a sentinel rather than NULL, so the foreign key
 * rejects the write. Resolving to NULL would instead store "no binary", which the source CHECK
 * reads as a CLASSPATH face -- a pointer quietly discarded rather than refused.
 */
internal fun assetAtAddress(tenantParam: String, catalogParam: String, keyParam: String): String =
    """
    CASE WHEN CAST(:$keyParam AS UUID) IS NULL THEN NULL
         ELSE COALESCE(
             (SELECT resource_id FROM assets
               WHERE tenant_key = :$tenantParam AND catalog_key = :$catalogParam AND id = :$keyParam),
             '00000000-0000-0000-0000-000000000000'::uuid)
    END
    """
