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
 * The last migration before the 1.x content store's remaining blobs moved (`V20261025110000`), and
 * before the fonts are converted (`V20261025105724`), which hashes a face's still-legacy bytes itself.
 */
internal const val BEFORE_LEGACY_CONTENT = "20261025083200"

/** The last migration before every asset's bytes shared one store and images moved to JSON storage (`V20261025134400`). */
internal const val BEFORE_IMAGE_STORAGE = "20261025133400"

/**
 * The last migration before a draft variant's description moved into its released form
 * (`V20261026085934`, contract `2.0.0-alpha.2`).
 */
internal const val BEFORE_VARIANT_DESCRIPTION = "20261025170800"

/** The last migration before templates moved to JSON storage and their tables went (ADR 0033, `V20261025170700`). */
internal const val BEFORE_TEMPLATE_STORAGE = "20261025150938"

/** The last migration before every catalog version became a row of `catalog_versions` (ADR 0036, `V20261026162943`). */
internal const val BEFORE_ONE_VERSION_TABLE = "20261026141633"

/** The last migration before a release's settings were filled in on its row from its manifest (ADR 0036, `V20261026170942`). */
internal const val BEFORE_RELEASE_SETTINGS_ON_ROW = "20261026162943"
