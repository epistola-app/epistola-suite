// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.changelog.AcknowledgeChangelog
import app.epistola.suite.changelog.ChangelogAudience
import app.epistola.suite.changelog.ChangelogService
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.SecurityContext
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class ChangelogHandler(
    private val changelogRenderer: ChangelogRenderer,
    private val changelogService: ChangelogService,
    private val buildProperties: BuildProperties?,
) {
    private val appVersion: String get() = buildProperties?.version ?: "dev"

    fun view(request: ServerRequest): ServerResponse {
        val version = request.queryParam("version")
        val audienceParam = request.queryParam("audience")
        val typeParam = request.queryParam("type")
        val scopeParam = request.queryParam("scope")

        val view = ChangelogAudience.viewFromParam(audienceParam)
        val type = typeParam?.trim()?.lowercase()?.takeUnless { it.isBlank() || it == "all" }
        val scope = scopeParam?.trim()?.takeUnless { it.isBlank() || it == "all" }

        // The dialog previews the in-progress [Unreleased] section as an "Upcoming" entry when it has visible items.
        val entries = changelogRenderer.entries(view, includeUnreleased = true, type = type, scope = scope)
        val availableTypes = changelogRenderer.availableTypes(view, includeUnreleased = true)
        val availableScopes = changelogRenderer.availableScopes(view, includeUnreleased = true)

        // Keep the requested version if it survives the filters; otherwise fall back to the latest shown.
        val selectedVersion = entries.find { it.version == version }?.version ?: entries.firstOrNull()?.version
        val selectedEntry = entries.find { it.version == selectedVersion }
        // Any selector is an in-dialog HTMX swap; a bare request renders the whole dialog.
        val isSelection = version != null || audienceParam != null || typeParam != null || scopeParam != null
        val fragment = if (isSelection) "layout" else "content"

        return ServerResponse.ok().render(
            "fragments/changelog :: $fragment",
            mapOf(
                "entries" to listOfNotNull(selectedEntry),
                "versions" to entries,
                "selectedVersion" to selectedVersion,
                "selectedView" to view.name.lowercase(),
                "selectedType" to (type ?: "all"),
                "selectedScope" to (scope ?: "all"),
                "availableTypes" to availableTypes,
                "availableScopes" to availableScopes,
            ),
        )
    }

    fun acknowledge(request: ServerRequest): ServerResponse {
        val principal = SecurityContext.current()
        // The dashboard "What's New" banner is user-facing — acknowledge against the user view so a
        // developer-only release does not become the effective version users are nagged about.
        val entries = changelogRenderer.entries(ChangelogAudience.USER)
        val version = changelogService.effectiveVersion(appVersion, entries)
        AcknowledgeChangelog(userId = principal.userId, version = version).execute()

        val latestEntry = entries.firstOrNull()
        return ServerResponse.ok().render(
            "fragments/changelog :: whats-new-muted",
            mapOf(
                "changelogVersion" to latestEntry?.version,
                "changelogSummary" to latestEntry?.summary,
            ),
        )
    }
}
