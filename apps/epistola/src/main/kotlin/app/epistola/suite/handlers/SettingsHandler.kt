package app.epistola.suite.handlers

import app.epistola.suite.feedback.SyncProviderType
import app.epistola.suite.feedback.commands.SaveFeedbackSyncConfig
import app.epistola.suite.feedback.queries.GetFeedbackSyncConfig
import app.epistola.suite.feedback.sync.github.GitHubSyncSettings
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper

@Component
class SettingsHandler(
    private val objectMapper: ObjectMapper,
) {
    fun feedbackSync(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val config = GetFeedbackSyncConfig(tenantId.key).query()

        val formData = mutableMapOf<String, String>()
        if (config != null) {
            formData["enabled"] = if (config.enabled) "on" else ""
            formData["providerType"] = config.providerType.name
            if (config.providerType == SyncProviderType.GITHUB) {
                val github = objectMapper.readValue(config.settings, GitHubSyncSettings::class.java)
                formData["installationId"] = github.installationId.toString()
                formData["repoOwner"] = github.repoOwner
                formData["repoName"] = github.repoName
                formData["label"] = github.label.orEmpty()
            }
            config.lastPolledAt?.let { formData["lastPolledAt"] = it.toString() }
        }

        return ServerResponse.ok().page("settings/feedback-sync") {
            "pageTitle" to "Feedback Sync Settings - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "settings"
            "formData" to formData
        }
    }

    fun saveFeedbackSync(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val enabled = request.param("enabled").isPresent

        val form = request.form {
            field("providerType") { required() }
            if (enabled) {
                field("repoOwner") {
                    required()
                    maxLength(100)
                }
                field("repoName") {
                    required()
                    maxLength(100)
                }
                field("installationId") {
                    required()
                    asInt()
                }
                field("label") { maxLength(100) }
            }
        }

        if (form.hasErrors()) {
            val combinedFormData = form.formData + Pair("enabled", if (enabled) "on" else "")
            return ServerResponse.ok().page("settings/feedback-sync") {
                "pageTitle" to "Feedback Sync Settings - Epistola"
                "tenantId" to tenantId.key
                "activeNavSection" to "settings"
                "formData" to combinedFormData
                "errors" to form.errors
            }
        }

        val providerType = SyncProviderType.valueOf(form["providerType"])
        val settingsJson = if (enabled) {
            objectMapper.writeValueAsString(
                GitHubSyncSettings(
                    installationId = form.getInt("installationId")!!.toLong(),
                    repoOwner = form["repoOwner"],
                    repoName = form["repoName"],
                    label = form["label"].ifBlank { null },
                ),
            )
        } else {
            "{}"
        }

        SaveFeedbackSyncConfig(
            tenantKey = tenantId.key,
            enabled = enabled,
            providerType = providerType,
            settings = settingsJson,
        ).execute()

        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.key}/settings/feedback-sync?saved=true")
            .build()
    }
}
