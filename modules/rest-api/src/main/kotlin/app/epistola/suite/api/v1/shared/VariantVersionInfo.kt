// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

internal data class VariantVersionInfo(
    val hasDraft: Boolean,
    val publishedVersions: List<Int>,
)
