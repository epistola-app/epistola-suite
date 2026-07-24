// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.model

/**
 * A single {code, label} entry of a code list.
 *
 * `hidden` entries remain valid for existing variants — variant validation
 * accepts them — but are filtered from pickers by default. This supports
 * deprecation, sunset, and tenant-curated subsets without breaking variants
 * that already use those codes.
 */
data class CodeListEntry(
    val code: String,
    val label: String,
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
)
