// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.environments.CatalogDeployment
import app.epistola.suite.templates.model.VariantSummary
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * How an application asks for one template: the identifiers the REST API's generate request takes,
 * a request body per variant ready to copy, and where the catalog is live. The template page's API
 * tab shows it, so a developer does not have to piece the identifiers together from three pages.
 *
 * The body names the first environment that serves the catalog, when one does: a request through an
 * environment renders the release deployed there. Without one, the catalog's highest release renders.
 */
data class ApplicationRequestView(
    val endpoint: String,
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val templateKey: String,
    /** The catalog's highest published release; null while nothing is published. */
    val latestPublished: String?,
    val environments: List<Serving>,
    /** The data example the bodies carry; null when the template has none, and the bodies send `{}`. */
    val exampleName: String?,
    val variants: List<VariantRequest>,
) {
    /** An environment serving the catalog: the release it serves, or null for a draft it follows. */
    data class Serving(val environmentKey: String, val version: String?)

    /** One variant, and the request body that asks for it. */
    data class VariantRequest(val key: String, val title: String, val isDefault: Boolean, val attributes: Map<String, String>, val body: String) {
        /** Lines to show the body in, up to a screenful; the rest scrolls. */
        val rows: Int get() = body.lines().size.coerceAtMost(14)
    }

    /** Nothing is published yet, so there is no release for an application to get. */
    val nothingPublished: Boolean get() = latestPublished == null

    /** The headers the request sends; shown, never sent, by the UI (the UI does not call the REST API). */
    val headers: List<String> get() = listOf("Authorization: ApiKey <key>", "Content-Type: $MEDIA_TYPE", "Accept: $MEDIA_TYPE")

    /** The environment the request bodies name, if the catalog is live anywhere. */
    val environmentKey: String? get() = environments.firstOrNull()?.environmentKey

    companion object {
        private const val MEDIA_TYPE = "application/vnd.epistola.v2+json"

        fun of(
            tenantKey: String,
            registry: String,
            namespace: String,
            catalogKey: String,
            templateKey: String,
            latestPublished: String?,
            deployments: List<CatalogDeployment>,
            variants: List<VariantSummary>,
            example: Pair<String, ObjectNode>?,
            objectMapper: ObjectMapper,
        ): ApplicationRequestView {
            val serving = deployments.map { Serving(it.environmentKey.value, it.version) }.distinctBy { it.environmentKey }
            val environmentKey = serving.firstOrNull()?.environmentKey
            fun body(variantKey: String): String {
                val node = objectMapper.createObjectNode()
                node.put("registry", registry)
                node.put("namespace", namespace)
                node.put("catalogKey", catalogKey)
                node.put("templateKey", templateKey)
                node.put("variantKey", variantKey)
                if (environmentKey != null) node.put("environmentKey", environmentKey)
                node.set("data", example?.second ?: objectMapper.createObjectNode())
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
            }
            return ApplicationRequestView(
                endpoint = "POST /api/tenants/$tenantKey/documents/generate",
                registry = registry,
                namespace = namespace,
                catalogKey = catalogKey,
                templateKey = templateKey,
                latestPublished = latestPublished,
                environments = serving,
                exampleName = example?.first,
                // The default first: it is the one most requests ask for.
                variants = variants.sortedByDescending { it.isDefault }.map {
                    VariantRequest(it.id.value, it.title, it.isDefault, it.attributes, body(it.id.value))
                },
            )
        }
    }
}
