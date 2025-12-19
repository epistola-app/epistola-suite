package app.epistola.suite.templates

import app.epistola.suite.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class DocumentTemplateRoutesTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbi: Jdbi

    @BeforeEach
    fun setUp() {
        jdbi.useHandle<Exception> { handle ->
            handle.execute("DELETE FROM document_templates")
            handle.execute(
                """
                INSERT INTO document_templates (name, content, created_at, last_modified)
                VALUES
                    ('Invoice Template', 'Invoice content', NOW(), NOW()),
                    ('Contract Template', 'Contract content', NOW(), NOW()),
                    ('Letter Template', 'Letter content', NOW(), NOW())
                """,
            )
        }
    }

    @Test
    fun `GET templates returns list page with template data`() {
        val response = restTemplate.getForEntity("/templates", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Document Templates")
        assertThat(response.body).contains("Invoice Template")
        assertThat(response.body).contains("Contract Template")
        assertThat(response.body).contains("Letter Template")
    }

    @Test
    fun `GET templates returns empty table when no templates exist`() {
        jdbi.useHandle<Exception> { handle ->
            handle.execute("DELETE FROM document_templates")
        }

        val response = restTemplate.getForEntity("/templates", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Document Templates")
        assertThat(response.body).doesNotContain("Invoice Template")
    }
}
