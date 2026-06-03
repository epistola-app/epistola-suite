package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.TestIdHelpers
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Guards the *class* of bug fixed by `RequestEncodingConfig`, not just the one
 * filter that triggered it.
 *
 * The original mojibake came from `PopupLoginFilter` reading a request parameter
 * at `HIGHEST_PRECEDENCE`, which forced the servlet to parse the form body before
 * the request encoding was set — locking it to ISO-8859-1. Gating that one filter
 * removed the symptom, but any *future* early-parameter-reading filter would bring
 * it back. `RequestEncodingConfig` pins the container's default request encoding to
 * UTF-8 up front, so the parse order no longer matters.
 *
 * This test installs exactly such a hostile filter — `@Order(HIGHEST_PRECEDENCE)`,
 * reading a parameter on every request — and asserts a diacritic-bearing form POST
 * still round-trips. Without the container-level fix this reproduces `CafÃ©`; with
 * it, the bug cannot be reintroduced by reading parameters early.
 */
@SpringBootTest(classes = [EpistolaSuiteApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(RequestEncodingLandmineTest.RogueEarlyParamFilterConfig::class)
class RequestEncodingLandmineTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val diacritics = "Café — naïve Größe ñ ë œuvre Žluťoučký kůň úpěl ďábelské ódy"

    @Test
    fun `form POST keeps UTF-8 even when a HIGHEST_PRECEDENCE filter reads a parameter first`() = fixture {
        data class Seed(val tenantKey: String, val templateId: TemplateId)
        lateinit var seed: Seed
        val slug = "landmine-${TestIdHelpers.nextTemplateId().value}".take(50).trimEnd('-')

        given {
            seed = withMediator {
                val tenant: Tenant = createTenant("Encoding Landmine")
                val tenantId = TenantId(tenant.id)
                Seed(tenantId.key.value, TemplateId(TemplateKey.of(slug), CatalogId.default(tenantId)))
            }
        }

        whenever {
            val name = URLEncoder.encode(diacritics, StandardCharsets.UTF_8)
            val body = "catalog=${seed.templateId.catalogKey.value}&slug=$slug&name=$name"
            val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
            val entity = HttpEntity(body.toByteArray(StandardCharsets.US_ASCII), headers)
            restTemplate.exchange(
                "/tenants/${seed.tenantKey}/templates",
                HttpMethod.POST,
                entity,
                String::class.java,
            )
        }

        then {
            val stored = withMediator { GetDocumentTemplate(id = seed.templateId).query() }
            assertThat(stored).isNotNull
            assertThat(stored!!.name).isEqualTo(diacritics)
        }
    }

    /**
     * A deliberately careless filter mimicking the original `PopupLoginFilter` bug:
     * it reads a request parameter at the highest precedence on every request,
     * forcing the servlet to parse the form body before any per-request encoding
     * filter runs. Harmless once the container default is UTF-8; the regression
     * canary if that guarantee is ever removed.
     */
    @TestConfiguration(proxyBeanMethods = false)
    class RogueEarlyParamFilterConfig {
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        fun rogueEarlyParamFilter(): OncePerRequestFilter = object : OncePerRequestFilter() {
            override fun doFilterInternal(
                request: HttpServletRequest,
                response: HttpServletResponse,
                filterChain: FilterChain,
            ) {
                // The hostile act: triggers eager form-body parsing before encoding filters.
                request.getParameter("trigger-body-parse")
                filterChain.doFilter(request, response)
            }
        }
    }
}
