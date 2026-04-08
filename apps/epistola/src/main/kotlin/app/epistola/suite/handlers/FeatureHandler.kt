package app.epistola.suite.handlers

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.features.queries.GetFeatureToggles
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

@Component
class FeatureHandler {
    fun featureToggles(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val toggles = GetFeatureToggles(tenantId.key).query()

        val features = toggles.map { (key, enabled) ->
            mapOf(
                "key" to key.value,
                "enabled" to enabled,
                "description" to (KnownFeatures.descriptions[key] ?: ""),
            )
        }

        return ServerResponse.ok().page("features") {
            "pageTitle" to "Feature Toggles - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "features"
            "features" to features
        }
    }

    fun saveFeatureToggles(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()

        KnownFeatures.all.forEach { featureKey ->
            val enabled = request.param(featureKey.value).isPresent
            SaveFeatureToggle(
                tenantKey = tenantId.key,
                featureKey = featureKey,
                enabled = enabled,
            ).execute()
        }

        return ServerResponse.status(303)
            .header("Location", "/tenants/${tenantId.key}/features?saved=true")
            .build()
    }
}
