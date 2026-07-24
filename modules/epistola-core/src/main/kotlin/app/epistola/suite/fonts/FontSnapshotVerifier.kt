// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.model.FontIntegrityException
import app.epistola.suite.fonts.queries.GetFontFamilyFingerprint
import app.epistola.suite.mediator.query
import app.epistola.suite.themes.ResolvedThemeSnapshot
import org.springframework.stereotype.Component

/**
 * Enforces deterministic-or-nothing for a *published* template version: the
 * pinned per-family font fingerprints (captured at publish into
 * [ResolvedThemeSnapshot.fontFingerprints]) must still match the live font
 * families. If a face was deleted, added, or re-uploaded with different bytes
 * since publish, the live [GetFontFamilyFingerprint] differs and the render
 * fails loudly via [FontIntegrityException] rather than silently re-rendering
 * with changed glyphs.
 *
 * Called pre-render in both render entry points
 * ([DocumentGenerationExecutor][app.epistola.suite.documents.batch.DocumentGenerationExecutor]
 * and [DocumentPreviewRenderer][app.epistola.suite.generation.DocumentPreviewRenderer])
 * inside the snapshot branch only — draft / live renders pin nothing and skip
 * verification entirely (an empty map is a no-op).
 *
 * The fingerprint key is `"${catalogKey ?: ""}/${slug}"`; an empty catalog
 * segment means the font ref was unqualified and resolves against
 * [owningCatalogKey] (the same cascade `PublishVersion` used to pin it).
 */
@Component
class FontSnapshotVerifier {

    fun verify(tenantKey: TenantKey, owningCatalogKey: CatalogKey, snapshot: ResolvedThemeSnapshot) {
        for ((key, pinned) in snapshot.fontFingerprints) {
            val sep = key.indexOf('/')
            val catalogSegment = if (sep >= 0) key.substring(0, sep) else ""
            val slugSegment = if (sep >= 0) key.substring(sep + 1) else key
            val slug = FontKey.validateOrNull(slugSegment) ?: continue
            val effCatalog =
                catalogSegment.takeIf { it.isNotBlank() }?.let(CatalogKey::of) ?: owningCatalogKey

            val current = GetFontFamilyFingerprint(tenantKey, effCatalog, slug).query()
            if (current != pinned) {
                throw FontIntegrityException(
                    "Font integrity check failed for family '${slug.value}' in catalog " +
                        "'${effCatalog.value}': the published template version pinned font " +
                        "fingerprint '$pinned' but the live family is now " +
                        "'${current ?: "MISSING (no faces)"}'. A font face was deleted, added, " +
                        "or re-uploaded with different bytes since this version was published. " +
                        "Republish the template version to adopt the changed font.",
                )
            }
        }
    }
}
