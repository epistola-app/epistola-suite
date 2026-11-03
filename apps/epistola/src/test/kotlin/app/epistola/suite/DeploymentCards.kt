// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite

/**
 * The markup of one environment's card on a catalog's Deployments page. That page draws one card per
 * environment rather than a table row, so a card runs from its `data-environment` up to where the next
 * one starts; the last card runs to the end of the page.
 */
internal fun deploymentCard(page: String, environment: String): String = page.split("data-environment=\"").firstOrNull { it.startsWith("$environment\"") }.orEmpty()
