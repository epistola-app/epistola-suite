// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

/**
 * The last migration before themes moved to JSON storage and their table went (ADR 0033,
 * `V20261024114440`). A migration test that checks what an earlier migration did to the `themes`
 * table migrates up to here; `ThemeStorageTest` and `DataPreservationMigrationIT` cover what follows.
 */
internal const val BEFORE_THEME_STORAGE = "20261024110723"

/** The last migration before fonts moved to JSON storage and their tables went (ADR 0033, `V20261025105724`). */
internal const val BEFORE_FONT_STORAGE = "20261025083200"

/**
 * The last migration before the 1.x content store's remaining blobs moved (`V20261025105700`): before
 * the fonts are converted, so a face whose bytes were still there joins its font.
 */
internal const val BEFORE_LEGACY_CONTENT = "20261025083200"

/** The last migration before every asset's bytes shared one store and images moved to JSON storage (`V20261025134400`). */
internal const val BEFORE_IMAGE_STORAGE = "20261025133400"
