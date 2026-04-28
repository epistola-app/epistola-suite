package app.epistola.suite.assets

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

@SpringBootTest(classes = [EpistolaSuiteApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AssetRoutesTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `POST assets returns validation error for unsupported media type`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Asset Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.MULTIPART_FORM_DATA
            headers.accept = listOf(MediaType.APPLICATION_JSON)

            val payload = LinkedMultiValueMap<String, Any>()
            payload.add(
                "file",
                HttpEntity(
                    object : ByteArrayResource("not-an-image".toByteArray()) {
                        override fun getFilename(): String = "report.xlsx"
                    },
                    HttpHeaders().apply {
                        contentType = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    },
                ),
            )
            payload.add("catalog", "default")

            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/assets",
                HttpEntity(payload, headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_JSON)
            assertThat(response.body).contains("Unsupported asset media type")
            assertThat(response.body).contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        }
    }
}
