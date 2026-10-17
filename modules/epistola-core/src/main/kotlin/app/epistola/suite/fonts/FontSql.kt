// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt

/**
 * The faces of the family at an address, joined so callers can keep naming a family by where it
 * lives while the rows themselves name the family's identity. The family is the one in its
 * catalog's current version, its faces those of the same version; a face's binary is found by its
 * identity in its own catalog's current version, since it may have relocated apart from the family.
 */
internal val FACES_OF_FAMILY_AT_ADDRESS: String =
    """
    FROM font_variants faces
    JOIN fonts family ON family.tenant_key = faces.tenant_key AND family.version_id = faces.version_id
                     AND family.resource_id = faces.font_resource_id
    LEFT JOIN assets binary_asset
           ON binary_asset.tenant_key = faces.tenant_key
          AND binary_asset.resource_id = faces.asset_resource_id
          AND ${inCurrentVersion("binary_asset")}
    WHERE family.tenant_key = :tenantKey AND family.version_id = ${versionAt("tenantKey", "catalogKey")} AND family.slug = :slug
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
               WHERE tenant_key = :$tenantParam AND version_id = ${versionAt(tenantParam, catalogParam)} AND id = :$keyParam),
             '00000000-0000-0000-0000-000000000000'::uuid)
    END
    """
