// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite

private val divTag = Regex("<div\\b|</div>")

/**
 * The markup of one environment's card on a catalog's Deployments page, from the `<div>` carrying its
 * `data-environment` to the `</div>` that closes it, so an assertion sees that card and nothing after it.
 *
 * A missing card fails here rather than returning nothing, so a `doesNotContain` on the result can never
 * pass only because the card it was meant to inspect is not on the page.
 */
internal fun deploymentCard(page: String, environment: String): String {
    val marker = page.indexOf("data-environment=\"$environment\"")
    check(marker >= 0) { "No deployment card for environment '$environment' on the page" }
    val start = page.lastIndexOf("<div", marker)
    var depth = 0
    for (tag in divTag.findAll(page, start)) {
        depth += if (tag.value == "</div>") -1 else 1
        if (depth == 0) return page.substring(start, tag.range.last + 1)
    }
    error("The deployment card for environment '$environment' is never closed")
}
