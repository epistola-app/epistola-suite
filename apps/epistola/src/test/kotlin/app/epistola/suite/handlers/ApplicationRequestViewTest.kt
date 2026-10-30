// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.environments.CatalogDeployment
import app.epistola.suite.templates.model.VariantSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/** What the template page's API tab tells a developer: identifiers, a body per variant, and what is live. */
class ApplicationRequestViewTest {

    private val mapper = ObjectMapper()

    private fun variant(key: String, title: String, default: Boolean = false, attributes: Map<String, String> = emptyMap()) = VariantSummary(VariantKey.of(key), title, attributes, default, ready = true)

    private fun deployment(environment: String, version: String?) = CatalogDeployment(
        environmentKey = EnvironmentKey.of(environment),
        catalogKey = CatalogKey.of("letters"),
        version = version,
        versionId = null,
        draftName = null,
        deployedAt = OffsetDateTime.parse("2026-10-30T12:00:00Z"),
        deployedBy = null,
    )

    private fun view(
        latestPublished: String? = "1.2.0",
        deployments: List<CatalogDeployment> = emptyList(),
        example: Boolean = true,
    ) = ApplicationRequestView.of(
        tenantKey = "acme",
        registry = "exchange.epistola.app",
        namespace = "acme",
        catalogKey = "letters",
        templateKey = "invoice",
        latestPublished = latestPublished,
        deployments = deployments,
        variants = listOf(variant("english", "English", attributes = mapOf("system.locale" to "en-US")), variant("default", "Default", default = true)),
        example = if (example) "Sample" to mapper.createObjectNode().put("customer", "Jane") else null,
        objectMapper = mapper,
    )

    @Test
    fun `each variant gets a body with the identifiers the generate request takes, the default first`() {
        val app = view()

        assertThat(app.endpoint).isEqualTo("POST /api/tenants/acme/documents/generate")
        assertThat(app.headers).contains("Authorization: ApiKey <key>", "Content-Type: application/vnd.epistola.v2+json")
        assertThat(app.variants.map { it.key }).containsExactly("default", "english")
        val body = mapper.readTree(app.variants.last().body)
        assertThat(body["registry"].asString()).isEqualTo("exchange.epistola.app")
        assertThat(body["namespace"].asString()).isEqualTo("acme")
        assertThat(body["catalogKey"].asString()).isEqualTo("letters")
        assertThat(body["templateKey"].asString()).isEqualTo("invoice")
        assertThat(body["variantKey"].asString()).isEqualTo("english")
        assertThat(body["data"]["customer"].asString()).`as`("the first data example").isEqualTo("Jane")
        assertThat(app.exampleName).isEqualTo("Sample")
    }

    @Test
    fun `a catalog live in an environment is asked for through it, one that is not gets no environment`() {
        val live = view(deployments = listOf(deployment("production", "1.2.0"), deployment("production", "1.1.0"), deployment("staging", null)))
        assertThat(live.environments).containsExactly(ApplicationRequestView.Serving("production", "1.2.0"), ApplicationRequestView.Serving("staging", null))
        assertThat(mapper.readTree(live.variants.first().body)["environmentKey"].asString()).isEqualTo("production")

        val notDeployed = view()
        assertThat(notDeployed.environmentKey).isNull()
        assertThat(mapper.readTree(notDeployed.variants.first().body).has("environmentKey")).isFalse()
    }

    @Test
    fun `with nothing published there is nothing to get, and without an example the data is empty`() {
        val app = view(latestPublished = null, example = false)

        assertThat(app.nothingPublished).isTrue()
        assertThat(app.exampleName).isNull()
        assertThat(mapper.readTree(app.variants.first().body)["data"].size()).isZero()
    }
}
