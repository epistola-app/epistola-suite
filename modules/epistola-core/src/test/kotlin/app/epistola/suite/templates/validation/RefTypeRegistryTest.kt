package app.epistola.suite.templates.validation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class RefTypeRegistryTest {

    @Test
    fun `findByUrl resolves the inline rich-text URL to its registered entry`() {
        val t = RefTypeRegistry.findByUrl("https://epistola.app/schemas/richtext-inline-v1.json")
        assertThat(t).isNotNull
        assertThat(t!!.id).isEqualTo("richTextInline")
        assertThat(t.label).isEqualTo("Rich text (inline)")
    }

    @Test
    fun `findByUrl resolves the block rich-text URL to its registered entry`() {
        val t = RefTypeRegistry.findByUrl("https://epistola.app/schemas/richtext-block-v1.json")
        assertThat(t).isNotNull
        assertThat(t!!.id).isEqualTo("richTextBlock")
        assertThat(t.label).isEqualTo("Rich text (block)")
    }

    @Test
    fun `findByUrl returns null for unknown URLs and null input`() {
        assertThat(RefTypeRegistry.findByUrl("https://example.com/other.json")).isNull()
        assertThat(RefTypeRegistry.findByUrl(null)).isNull()
        assertThat(RefTypeRegistry.findByUrl("")).isNull()
    }

    @Test
    fun `preloadedSchemaResources covers every registered entry`() {
        val pairs = RefTypeRegistry.preloadedSchemaResources()
        assertThat(pairs).hasSize(RefTypeRegistry.ALL.size)
        assertThat(pairs.map { it.first }).containsExactlyInAnyOrderElementsOf(
            RefTypeRegistry.ALL.map { it.url },
        )
        assertThat(pairs.map { it.second }).allMatch { it.startsWith("/") && it.endsWith(".json") }
    }

    @Test
    fun `every registered schema body is loadable from the classpath`() {
        // Catches typos when someone adds a new entry without dropping the file
        // in modules/epistola-core/src/main/resources/schemas/.
        RefTypeRegistry.ALL.forEach { refType ->
            val body = RefTypeRegistry::class.java.getResource(refType.schemaResourcePath)
            assertThat(body)
                .withFailMessage("Schema resource missing for ${refType.id}: ${refType.schemaResourcePath}")
                .isNotNull
            val content = body!!.readText(Charsets.UTF_8)
            assertThat(content).isNotBlank
            assertThat(content).contains("\"\$id\"").contains(refType.url)
        }
    }
}
