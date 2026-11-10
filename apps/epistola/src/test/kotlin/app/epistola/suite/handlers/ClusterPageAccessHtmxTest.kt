// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.testing.TEST_PLATFORM_ROLES_HEADER
import app.epistola.suite.testing.TEST_TENANT_MEMBERSHIPS_HEADER
import app.epistola.suite.testing.TEST_TENANT_ROLES_HEADER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * The cluster page shows the installation — its nodes, scheduled tasks and timers, which name every
 * tenant — so it is a platform page, for the installation administrator and the platform observer,
 * and no tenant's URL reaches it.
 */
class ClusterPageAccessHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var nodeIdentity: NodeIdentity

    private fun get(path: String, header: String, value: String) = restTemplate.exchange(
        path,
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { set(header, value) }),
        String::class.java,
    )

    @Test
    fun `a tenant's URL shows a member of it no cluster, whatever their role`() {
        val own = createTenant("Cluster Viewer")

        val page = get("/tenants/${own.id.value}/cluster", TEST_TENANT_ROLES_HEADER, "CONTENT_VIEWER")
        val refresh = restTemplate.exchange(
            "/tenants/${own.id.value}/cluster/refresh",
            HttpMethod.GET,
            HttpEntity<Void>(
                HttpHeaders().apply {
                    set(TEST_TENANT_ROLES_HEADER, "CONTENT_VIEWER")
                    set("HX-Request", "true")
                },
            ),
            String::class.java,
        )

        assertThat(page.statusCode).isNotEqualTo(HttpStatus.OK)
        assertThat(page.body.orEmpty()).doesNotContain(nodeIdentity.nodeId)
        assertThat(refresh.statusCode).isNotEqualTo(HttpStatus.OK)
        assertThat(refresh.body.orEmpty()).doesNotContain(nodeIdentity.nodeId)
    }

    @Test
    fun `no tenant's URL shows the cluster, also not to a member of another tenant`() {
        val own = createTenant("Cluster Own")
        val other = createTenant("Cluster Other")

        val response = get("/tenants/${other.id.value}/cluster", TEST_TENANT_MEMBERSHIPS_HEADER, own.id.value)

        assertThat(response.statusCode).isNotEqualTo(HttpStatus.OK)
        assertThat(response.body.orEmpty()).doesNotContain(nodeIdentity.nodeId)
    }

    @Test
    fun `a signed-in user without a platform role is refused the cluster page`() {
        val own = createTenant("Cluster Member")

        val response = get("/platform/cluster", TEST_TENANT_MEMBERSHIPS_HEADER, own.id.value)

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body.orEmpty()).doesNotContain(nodeIdentity.nodeId)
    }

    @Test
    fun `an installation administrator sees the cluster page`() {
        val response = get("/platform/cluster", TEST_PLATFORM_ROLES_HEADER, "INSTALLATION_ADMINISTRATOR")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains(nodeIdentity.nodeId)
    }

    @Test
    fun `a platform observer sees the cluster page, and cannot forget a node`() {
        val page = get("/platform/cluster", TEST_PLATFORM_ROLES_HEADER, "PLATFORM_OBSERVER")
        assertThat(page.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(page.body).contains(nodeIdentity.nodeId)

        val forget = restTemplate.exchange(
            "/platform/cluster/nodes/some-node/forget",
            HttpMethod.POST,
            HttpEntity<Void>(HttpHeaders().apply { set(TEST_PLATFORM_ROLES_HEADER, "PLATFORM_OBSERVER") }),
            String::class.java,
        )
        assertThat(forget.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }
}
