// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.catalog.protocol.AttributeAssignment
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.UpdateCatalogMetadata
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A badge is `white-space: nowrap`, so a long unbroken value in a badge list has nothing to break
 * on. The manual test report could see the badge leaving its card but not whether it also stretched
 * the page, which is the difference between cosmetic and not; measured, it moved the document by
 * 218px, so every column on the page shifted.
 *
 * Keywords are capped at 30 characters now, but discovery attributes are not: the value half of
 * `catalog.key: value` is free text with no limit, and subscribed catalogs bring whatever the
 * publisher put there. So the row still has to cope, and this measures the case that can still
 * occur rather than the one validation now prevents.
 */
class CatalogKeywordUiTest : BasePlaywrightTest() {

    private val longValue = "bezwaarschriftenprocedure-".repeat(4) + "afhandeling"

    @Test
    fun `a very long badge value stays inside the page`() {
        val tenant = createTenant("Badge Overflow")
        val catalogKey = CatalogKey.of("overflow")
        withMediator {
            CreateCatalog(tenant.id, catalogKey, "Overflow").execute()
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("onderwerp"), CatalogId(catalogKey, TenantId(tenant.id))),
                displayName = "Onderwerp",
                allowedValues = listOf(longValue),
            ).execute()
            UpdateCatalogMetadata(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
                name = "Overflow",
                description = null,
                attributes = listOf(AttributeAssignment("overflow", "onderwerp", longValue)),
            ).execute()
        }

        gotoAndReady("/tenants/${tenant.id}/catalogs/${catalogKey.value}/browse")
        page.htmxSettle()

        val overflow = page.evaluate(
            """
            () => {
                const el = document.scrollingElement;
                const badge = document.querySelector('.badge-list .badge');
                const card = badge && badge.closest('.catalog-detail-item');
                return {
                    documentOverflow: el.scrollWidth - el.clientWidth,
                    badgeOverflow: badge && card
                        ? Math.round(badge.getBoundingClientRect().right - card.getBoundingClientRect().right)
                        : null,
                };
            }
            """,
        ) as Map<*, *>

        // The page never scrolls sideways: whatever the badge does, it does inside the layout.
        assertThat((overflow["documentOverflow"] as Number).toInt())
            .withFailMessage(
                "A long badge value widened the document by %spx, so the whole page scrolls sideways.",
                overflow["documentOverflow"],
            )
            .isLessThanOrEqualTo(0)

        // And it stays within its own card rather than running over the neighbouring column.
        assertThat((overflow["badgeOverflow"] as Number).toInt())
            .withFailMessage(
                "The badge ran %spx past the right edge of its card.",
                overflow["badgeOverflow"],
            )
            .isLessThanOrEqualTo(0)
    }
}
