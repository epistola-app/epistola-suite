package app.epistola.suite.documents.queries

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class RecentUsageShapeProjectorTest {

    private val projector = RecentUsageShapeProjector()
    private val objectMapper = ObjectMapper()

    private fun obj(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    @Test
    fun `present and absent values at an affected path produce different keys`() {
        val present = projector.key(obj("""{"taxId":"123"}"""), listOf("taxId"))
        val absent = projector.key(obj("""{"name":"A"}"""), listOf("taxId"))

        assertThat(present).isNotEqualTo(absent)
    }

    @Test
    fun `payloads differing only outside the affected paths share a key`() {
        val a = projector.key(obj("""{"taxId":"123","name":"Alice"}"""), listOf("taxId"))
        val b = projector.key(obj("""{"taxId":"123","name":"Bob"}"""), listOf("taxId"))

        // name is not an affected path, so both collapse to one shape — the whole point of the dedup.
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `distinct values at an affected path produce distinct keys`() {
        val c = projector.key(obj("""{"status":"C"}"""), listOf("status"))
        val a = projector.key(obj("""{"status":"A"}"""), listOf("status"))

        assertThat(c).isNotEqualTo(a)
    }

    @Test
    fun `array element values are captured under a bracketed path`() {
        val paths = listOf("items[].sku")
        val two = projector.key(obj("""{"items":[{"sku":"x"},{"sku":"y"}]}"""), paths)
        val one = projector.key(obj("""{"items":[{"sku":"x"}]}"""), paths)
        val missingSku = projector.key(obj("""{"items":[{"other":"x"}]}"""), paths)

        assertThat(two).isNotEqualTo(one)
        assertThat(one).isNotEqualTo(missingSku)
    }

    @Test
    fun `a missing array yields the absent marker rather than throwing`() {
        val key = projector.key(obj("""{"name":"A"}"""), listOf("items[].sku"))

        // No exception, and it reads as absent — distinct from any present array.
        assertThat(key).isNotEqualTo(projector.key(obj("""{"items":[]}"""), listOf("items[].sku")))
    }

    @Test
    fun `nested object paths are navigated`() {
        val paths = listOf("customer.taxId")
        val withTax = projector.key(obj("""{"customer":{"taxId":"1"}}"""), paths)
        val withoutTax = projector.key(obj("""{"customer":{"name":"A"}}"""), paths)

        assertThat(withTax).isNotEqualTo(withoutTax)
    }
}
