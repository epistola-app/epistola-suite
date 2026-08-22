// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus
import tools.jackson.databind.ObjectMapper

class ResourceGraphRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `feature is unavailable by default`() {
        val tenant = createTenant("Graph Routes")

        val response = restTemplate.getForEntity("/tenants/${tenant.id}/resource-graph", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `enabled feature renders the standalone graph explorer`() {
        val tenant = createTenant("Graph Routes")
        withMediator { SaveFeatureToggle(tenant.id, KnownFeatures.RESOURCE_GRAPH, enabled = true).execute() }

        val response = restTemplate.getForEntity("/tenants/${tenant.id}/resource-graph", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("<ep-resource-graph", "Resource graph")
        assertThat(response.body).contains("data-relocation-enabled=\"false\"")
    }

    @Test
    fun `relocation controls require their independent alpha toggle`() {
        val tenant = createTenant("Graph relocation")
        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.RESOURCE_GRAPH, enabled = true).execute()
        }

        val disabledPreview = restTemplate.getForEntity(
            "/tenants/${tenant.id}/resource-graph/move-preview?type=stencil&catalog=source&key=header&targetCatalog=target",
            String::class.java,
        )
        assertThat(disabledPreview.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.RESOURCE_RELOCATION, enabled = true).execute()
        }
        val page = restTemplate.getForEntity("/tenants/${tenant.id}/resource-graph", String::class.java)
        assertThat(page.body).contains("data-relocation-enabled=\"true\"")
    }

    @Test
    fun `nodes endpoint searches resources through the UI route`() {
        val tenant = createTenant("Graph Search")
        withMediator { SaveFeatureToggle(tenant.id, KnownFeatures.RESOURCE_GRAPH, enabled = true).execute() }

        val response = restTemplate.getForEntity("/tenants/${tenant.id}/resource-graph/nodes?q=inter&type=font", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val nodes = objectMapper.readTree(response.body).path("nodes")
        assertThat(nodes.values().map { it.path("key").stringValue() }).contains("inter")
        assertThat(nodes.values()).allSatisfy { node -> assertThat(node.path("type").stringValue()).isEqualTo("font") }
    }
}
