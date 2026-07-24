// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.model

/**
 * Where a code list's entries come from.
 *
 * - `CLASSPATH` — entries shipped with Epistola at a `classpath:` URL. Refreshed
 *   from the bundled JSON on demand.
 * - `URL` — entries fetched over HTTPS from a tenant-managed endpoint. Refreshed
 *   on demand via the "Refresh" button.
 * - `INLINE` — entries entered directly in the UI. No external source.
 */
enum class CodeListSource {
    CLASSPATH,
    URL,
    INLINE,
}
