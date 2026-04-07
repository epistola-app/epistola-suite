package app.epistola.suite.handlers

import app.epistola.suite.changelog.AcknowledgeChangelog
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
        val entries = changelogRenderer.entries()

        return if (version != null) {
            val entry = entries.find { it.version == version }
            ServerResponse.ok().render(
                "fragments/changelog :: version-content",
                mapOf("entries" to listOfNotNull(entry)),
            )
        } else {
            val latestEntry = entries.firstOrNull()
            ServerResponse.ok().render(
                "fragments/changelog :: content",
                mapOf(
                    "entries" to listOfNotNull(latestEntry),
                    "versions" to entries,
                    "selectedVersion" to latestEntry?.version,
                ),
            )
        }
    }

    fun acknowledge(request: ServerRequest): ServerResponse {
        val principal = SecurityContext.current()
        val entries = changelogRenderer.entries()
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
