// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime
import java.util.UUID

class ConsumerStatusHandlerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbi: Jdbi

    /** Seeds an API key (the consumer identity) via the command path; returns its id for linking nodes. */
    private fun seedApiKey(tenant: Tenant, name: String): UUID = withMediator {
        CreateApiKey(tenantId = tenant.id, name = name).execute().apiKey.id.value
    }

    private fun seedNode(tenant: Tenant, consumerId: UUID, nodeId: String, partitions: List<Int>) {
        val partitionsJson = partitions.joinToString(prefix = "[", postfix = "]")
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO consumer_node_assignments (tenant_key, consumer_id, node_id, partitions, last_seen_at)
                VALUES (:tenantKey, :consumerId, :nodeId, :partitions::jsonb, :now)
                """,
            )
                .bind("tenantKey", tenant.id)
                .bind("consumerId", consumerId.toString())
                .bind("nodeId", nodeId)
                .bind("partitions", partitionsJson)
                .bind("now", OffsetDateTime.now())
                .execute()
        }
    }

    @Test
    fun `GET dashboard renders the consumers page with seeded labels and node ids`() = fixture {
        lateinit var tenant: Tenant
        lateinit var keyName: String
        lateinit var nodeId: String

        given {
            tenant = tenant("Operations Test Tenant")
            keyName = "Acme Plugin (prod)"
            nodeId = "plugin-instance-1"
            val consumerId = seedApiKey(tenant, keyName)
            seedNode(tenant, consumerId, nodeId, listOf(0, 5))
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${tenant.id}/consumers",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            assertThat(body).contains("Consumers") // page header
            assertThat(body).contains(keyName)
            assertThat(body).contains(nodeId)
            assertThat(body).contains("Active nodes")
            // Auto-refresh trigger embedded in the fragment.
            assertThat(body).contains("hx-trigger=\"every 10s\"")
        }
    }

    @Test
    fun `GET dashboard shows empty state when tenant has no api keys`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("No-Consumers Tenant")
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${tenant.id}/consumers",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            assertThat(body).contains("No consumers yet")
        }
    }

    @Test
    fun `GET refresh under HTMX returns the inner fragment without the layout shell`() = fixture {
        lateinit var tenant: Tenant
        lateinit var keyName: String

        given {
            tenant = tenant("Refresh Tenant")
            keyName = "Refresh Plugin"
            seedApiKey(tenant, keyName)
        }

        whenever {
            val headers = HttpHeaders()
            headers.set("HX-Request", "true")
            restTemplate.exchange(
                "/tenants/${tenant.id}/consumers/refresh",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // Fragment-only response: no surrounding <html>/<body> shell.
            assertThat(body).doesNotContain("<html")
            assertThat(body).doesNotContain("app-nav")
            // But it does contain the consumer's data.
            assertThat(body).contains(keyName)
            assertThat(body).contains("id=\"consumer-results\"")
        }
    }

    @Test
    fun `GET refresh without HTMX header redirects back to the dashboard`() = fixture {
        lateinit var tenant: Tenant

        given { tenant = tenant("Direct Refresh Tenant") }

        whenever {
            restTemplate.exchange(
                "/tenants/${tenant.id}/consumers/refresh",
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders()),
                String::class.java,
            )
        }

        then {
            // TestRestTemplate auto-follows redirects, so we observe the final 200 dashboard
            // — proof that the non-HTMX path redirected to `/consumers` rather than rendering
            // a bare fragment.
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body!!).contains("No consumers yet")
            assertThat(response.body!!).contains("<html")
        }
    }
}
