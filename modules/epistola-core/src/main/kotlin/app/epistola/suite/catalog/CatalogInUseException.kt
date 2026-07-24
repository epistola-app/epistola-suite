// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

class CatalogInUseException(
    val catalogKey: CatalogKey,
    val references: List<String>,
) : RuntimeException(
    "Cannot delete catalog '${catalogKey.value}': referenced by ${references.joinToString(", ")}",
)
