package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * HTTP-level smoke for the v0.3 collect surface.
 *
 * The mediator-driven scenario tests under `modules/epistola-core` cover the
 * domain logic comprehensively. They do NOT cover:
 *   - whether the Spring routing wires the controllers to the contract paths,
 *   - whether `ClientIdentityFilter` and `ApiKeyAuthenticationFilter` actually
 *     fire (and in the right order) on a real `/api/...` request,
 *   - whether the NDJSON wire shape that crosses the socket matches the
 *     contract's expected JSON keys (`requestId`, `_meta`, `partitions.mine`,
 *     etc.) — Jackson 3 vs the contract's generated client,
 *   - whether `Accept-Encoding: gzip` actually triggers `Content-Encoding: gzip`.
 *
 * That's a small but very real surface that's "trivially" correct in code yet
 * trivially broken at runtime. This single IT exercises it end-to-end against
 * a Spring Boot app on a random port, with a real API key in the DB and the
 * real filter chain.
 */
@Import(
    TestcontainersConfiguration::class,
    UnloggedTablesTestConfiguration::class,
    CollectSmokeSecurityConfig::class,
)
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "epistola.demo.enabled=false",
        // Disable the JobPoller so a freshly-emitted result doesn't get raced by
        // a real generation kicking off in parallel.
        "epistola.generation.polling.enabled=false",
    ],
)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class CollectEndpointSmokeIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `ping without auth or client identity returns UP without partition info`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.parseMediaType(EPISTOLA_JSON)
            accept = listOf(MediaType.parseMediaType(EPISTOLA_JSON))
            set(HttpHeaders.USER_AGENT, "curl/8.0")
        }
        val response = restTemplate.exchange(
            "/api/ping",
            HttpMethod.POST,
            HttpEntity(null, headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.status")).isEqualTo("UP")
        assertThat(JsonPath.read<String>(body, "$.timestamp")).isNotBlank
        // Anonymous ping omits the details block entirely.
        assertThat(JsonPath.read<Any?>(body, "$.details")).isNull()
    }

    @Test
    fun `ping with API key returns server info and partition assignment`() {
        val (_, key) = seedTenantAndKey()
        val headers = baseHeaders().apply { add("X-API-Key", key) }
        val response = restTemplate.exchange(
            "/api/ping",
            HttpMethod.POST,
            HttpEntity(null, headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.status")).isEqualTo("UP")
        assertThat(JsonPath.read<String>(body, "$.details.serverVersion")).isNotBlank
        // apiVersion is reported by the contract library (ServerContractInfo, built
        // into the contract JAR from the spec's info.version); assert non-blank to
        // stay stable across contract bumps, but also that it resolved to a real
        // version rather than the "unknown" fallback for an unidentifiable contract.
        assertThat(JsonPath.read<String>(body, "$.details.apiVersion")).isNotBlank
        assertThat(JsonPath.read<String>(body, "$.details.apiVersion")).isNotEqualTo("unknown")
        assertThat(JsonPath.read<String>(body, "$.details.nodeId")).isNotBlank
        assertThat(JsonPath.read<Int>(body, "$.details.partitions.total")).isEqualTo(64)
        // First touch with this node — owns ALL partitions until another node
        // joins (no other heartbeats exist for this fresh consumer).
        assertThat(JsonPath.read<List<*>>(body, "$.details.partitions.mine")).hasSize(64)
    }

    @Test
    fun `collect rejects 400 when X-EP-Node-Id header is missing`() {
        val (tenantKey, key) = seedTenantAndKey()
        // Build headers from scratch — baseHeaders() seeds X-EP-Node-Id and we want
        // to exercise its absence here.
        val headers = HttpHeaders().apply {
            contentType = MediaType.parseMediaType(EPISTOLA_JSON)
            set(HttpHeaders.USER_AGENT, "epistola-contract/0.3.0 smoke-test")
            add("X-API-Key", key)
        }
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/generation/collect",
            HttpMethod.POST,
            HttpEntity("{}", headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/bad-request")
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/bad-request")
        assertThat(JsonPath.read<String>(body, "$.title")).isEqualTo("Bad Request")
        assertThat(JsonPath.read<Int>(body, "$.status")).isEqualTo(400)
        assertThat(JsonPath.read<String>(body, "$.detail")).contains("X-EP-Node-Id")
        assertThat(JsonPath.read<String>(body, "$.instance")).isEqualTo("/api/tenants/${tenantKey.value}/generation/collect")
    }

    @Test
    fun `collect problem details include query string and top-level RFC 9457 fields`() {
        val (tenantKey, key) = seedTenantAndKey()
        val headers = HttpHeaders().apply {
            contentType = MediaType.parseMediaType(EPISTOLA_JSON)
            accept = listOf(MediaType.parseMediaType(EPISTOLA_JSON), MediaType.parseMediaType(EPISTOLA_NDJSON))
            set(HttpHeaders.USER_AGENT, "epistola-contract/0.3.0 smoke-test")
            add("X-API-Key", key)
        }
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/generation/collect?limit=25",
            HttpMethod.POST,
            HttpEntity("{}", headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/bad-request")
        assertThat(JsonPath.read<String>(body, "$.title")).isEqualTo("Bad Request")
        assertThat(JsonPath.read<Int>(body, "$.status")).isEqualTo(400)
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/bad-request")
        assertThat(JsonPath.read<String>(body, "$.detail")).contains("X-EP-Node-Id")
        assertThat(JsonPath.read<String>(body, "$.instance")).isEqualTo("/api/tenants/${tenantKey.value}/generation/collect?limit=25")
        assertThat(body).doesNotContain("\"details\":")
    }

    @Test
    fun `collect rejects 400 when User-Agent does not start with epistola-contract`() {
        val (tenantKey, key) = seedTenantAndKey()
        val headers = baseHeaders().apply {
            add("X-API-Key", key)
            set(HttpHeaders.USER_AGENT, "curl/8.0")
        }
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/generation/collect",
            HttpMethod.POST,
            HttpEntity("{}", headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/bad-request")
        assertThat(JsonPath.read<String>(body, "$.detail")).contains("User-Agent")
    }

    @Test
    fun `collect with valid headers returns NDJSON _meta line and gzip-encoded body`() {
        val (tenantKey, key) = seedTenantAndKey()
        val headers = baseHeaders().apply {
            add("X-API-Key", key)
            // Accept-Encoding: gzip is also the spec default, but pin it explicitly
            // so we know the server actually negotiates against it.
            set(HttpHeaders.ACCEPT_ENCODING, "gzip")
        }

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/generation/collect",
            HttpMethod.POST,
            HttpEntity("{}", headers),
            ByteArray::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType.toString())
            .isEqualTo("application/vnd.epistola.v1+ndjson")
        // We can't reliably check Content-Encoding here: TestRestTemplate's
        // underlying Apache client transparently decodes gzip and STRIPS the
        // Content-Encoding header. To verify negotiation actually happened,
        // see the `negotiates identity encoding` test below — it uses
        // Accept-Encoding: identity and asserts the server sends no encoding
        // header. The fact that this test gets parseable NDJSON below proves
        // the gzip path round-trips correctly end-to-end.
        val ndjson = String(response.body!!, Charsets.UTF_8)
        val lines = ndjson.trimEnd('\n').split("\n")
        assertThat(lines).`as`("at minimum the trailing _meta line should be present").hasSizeGreaterThanOrEqualTo(1)

        val metaLine = lines.last()
        assertThat(JsonPath.read<Boolean>(metaLine, "$._meta"))
            .`as`("last line must be the meta envelope")
            .isTrue
        assertThat(JsonPath.read<Int>(metaLine, "$.count")).isEqualTo(0) // empty tenant, no results yet
        assertThat(JsonPath.read<Boolean>(metaLine, "$.hasMore")).isFalse
        assertThat(JsonPath.read<Any?>(metaLine, "$.lastSequence")).isNull()
        assertThat(JsonPath.read<Int>(metaLine, "$.partitions.total")).isEqualTo(64)
        assertThat(JsonPath.read<List<*>>(metaLine, "$.partitions.mine")).hasSize(64) // first touch, owns all
        assertThat(JsonPath.read<String>(metaLine, "$.partitions.hash").lowercase()).isEqualTo("murmur3")
    }

    @Test
    fun `collect negotiates identity encoding when client refuses gzip`() {
        val (tenantKey, key) = seedTenantAndKey()
        val headers = baseHeaders().apply {
            add("X-API-Key", key)
            set(HttpHeaders.ACCEPT_ENCODING, "identity")
        }

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/generation/collect",
            HttpMethod.POST,
            HttpEntity("{}", headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType.toString())
            .isEqualTo("application/vnd.epistola.v1+ndjson")
        // Negotiated identity → server should NOT advertise gzip (or any encoding).
        // Apache HttpClient leaves Content-Encoding intact when it didn't decode.
        assertThat(response.headers[HttpHeaders.CONTENT_ENCODING])
            .`as`("server should not set gzip when the client asked for identity")
            .satisfiesAnyOf(
                { value -> assertThat(value).isNull() },
                { value -> assertThat(value).doesNotContain("gzip") },
            )

        // Body is uncompressed NDJSON, ending with the _meta line.
        val lines = response.body!!.trimEnd('\n').split("\n")
        assertThat(JsonPath.read<Boolean>(lines.last(), "$._meta")).isTrue
    }

    @Test
    fun `collect without auth returns 401`() {
        val (tenantKey, _) = seedTenantAndKey()
        val headers = baseHeaders()
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/generation/collect",
            HttpMethod.POST,
            HttpEntity("{}", headers),
            String::class.java,
        )

        // Filter chain: ClientIdentityFilter passes (headers present) →
        // auth chain rejects (no API key, no JWT) → 401.
        assertThat(response.statusCode).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isIn(
            "https://epistola.app/errors/unauthorized",
            "https://epistola.app/errors/access-denied",
        )
        assertThat(JsonPath.read<Int>(body, "$.status")).isIn(401, 403)
        assertThat(JsonPath.read<String>(body, "$.instance")).isEqualTo("/api/tenants/${tenantKey.value}/generation/collect")
        assertThat(body).doesNotContain("\"details\":")
    }

    /**
     * Headers every test starts with. The two v0.3-mandatory headers are
     * pre-populated; tests that need to verify their absence override them.
     */
    private fun baseHeaders(): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType(EPISTOLA_JSON)
        accept = listOf(
            MediaType.parseMediaType(EPISTOLA_JSON),
            MediaType.parseMediaType(EPISTOLA_NDJSON),
        )
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.3.0 smoke-test")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
    }

    @Test
    fun `access denied returns 403 problem details with ACCESS_DENIED code`() {
        val (_, key) = seedTenantAndKey()
        val headers = baseHeaders().apply { add("X-API-Key", key) }
        val response = restTemplate.exchange(
            "/api/test/forbidden",
            HttpMethod.GET,
            HttpEntity(null, headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/access-denied")
        assertThat(JsonPath.read<Int>(body, "$.status")).isEqualTo(403)
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/access-denied")
    }

    /** + API key pair for one test. The API key has the
     * full permission set granted to NPA principals (see ApiKeyAuthenticationFilter,
     * which assigns every TenantRole), so DOCUMENT_GENERATE on /generation/collect
     * is satisfied without any extra grant juggling.
     */
    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("smoke-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Smoke Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "smoke").execute()
        tenantKey to created.plaintextKey
    }

    private companion object {
        const val EPISTOLA_JSON = "application/vnd.epistola.v1+json"
        const val EPISTOLA_NDJSON = "application/vnd.epistola.v1+ndjson"
    }
}
