package app.epistola.catalog.protocol

import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader
import tools.jackson.databind.ObjectMapper

class ResourceDetailDeserializationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    @Test
    fun `deserialize template resource detail`() {
        val json = resourceLoader.getResource("classpath:demo/catalog/resources/templates/hello-world.json").contentAsByteArray
        val detail = objectMapper.readValue(json, ResourceDetail::class.java)

        assertThat(detail.schemaVersion).isEqualTo(2)
        assertThat(detail.resource).isInstanceOf(TemplateResource::class.java)

        val template = detail.resource as TemplateResource
        assertThat(template.slug).isEqualTo("hello-world")
        assertThat(template.name).isEqualTo("Hello World")
        assertThat(template.variants).isNotEmpty()
    }

    @Test
    fun `deserialize theme resource detail`() {
        val json = resourceLoader.getResource("classpath:demo/catalog/resources/themes/corporate.json").contentAsByteArray
        val detail = objectMapper.readValue(json, ResourceDetail::class.java)

        assertThat(detail.schemaVersion).isEqualTo(2)
        assertThat(detail.resource).isInstanceOf(ThemeResource::class.java)

        val theme = detail.resource as ThemeResource
        assertThat(theme.slug).isEqualTo("corporate")
        assertThat(theme.name).isEqualTo("Corporate Theme")
        assertThat(theme.documentStyles).isNotEmpty()
    }

    @Test
    fun `deserialize attribute resource detail`() {
        val json = resourceLoader.getResource("classpath:demo/catalog/resources/attributes/language.json").contentAsByteArray
        val detail = objectMapper.readValue(json, ResourceDetail::class.java)

        assertThat(detail.schemaVersion).isEqualTo(2)
        assertThat(detail.resource).isInstanceOf(AttributeResource::class.java)

        val attr = detail.resource as AttributeResource
        assertThat(attr.slug).isEqualTo("language")
        assertThat(attr.allowedValues).containsExactly("nl", "en", "de", "fr")
    }

    @Test
    fun `deserialize stencil resource detail`() {
        val json = resourceLoader.getResource("classpath:demo/catalog/resources/stencils/company-header.json").contentAsByteArray
        val detail = objectMapper.readValue(json, ResourceDetail::class.java)

        assertThat(detail.schemaVersion).isEqualTo(2)
        assertThat(detail.resource).isInstanceOf(StencilResource::class.java)

        val stencil = detail.resource as StencilResource
        assertThat(stencil.slug).isEqualTo("company-header")
        assertThat(stencil.tags).containsExactly("header", "branding")
    }
}
