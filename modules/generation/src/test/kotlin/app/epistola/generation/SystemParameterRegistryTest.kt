package app.epistola.generation

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SystemParameterRegistryTest {
    @Test
    fun `registry contains pages current descriptor`() {
        val descriptors = SystemParameterRegistry.all()
        assertTrue(descriptors.size >= 2, "Registry should have at least two descriptors")

        val pagesCurrent = descriptors.find { it.path == "pages.current" }
        assertTrue(pagesCurrent != null, "pages.current descriptor should be registered")
        assertEquals("integer", pagesCurrent.type)
        assertEquals(SystemParamScope.PAGE_SCOPED, pagesCurrent.scope)
        assertEquals("sys.pages.current", pagesCurrent.fullPath)
        assertEquals(1, pagesCurrent.mockValue)
    }

    @Test
    fun `registry contains pages total descriptor`() {
        val descriptors = SystemParameterRegistry.all()

        val pagesTotal = descriptors.find { it.path == "pages.total" }
        assertTrue(pagesTotal != null, "pages.total descriptor should be registered")
        assertEquals("integer", pagesTotal.type)
        assertEquals(SystemParamScope.GLOBAL, pagesTotal.scope)
        assertEquals("sys.pages.total", pagesTotal.fullPath)
        assertEquals(1, pagesTotal.mockValue)
    }

    @Test
    fun `registry contains render time descriptor`() {
        val descriptors = SystemParameterRegistry.all()

        val renderTime = descriptors.find { it.path == "render.time" }
        assertTrue(renderTime != null, "render.time descriptor should be registered")
        assertEquals("datetime", renderTime.type)
        assertEquals(SystemParamScope.GLOBAL, renderTime.scope)
        assertEquals("sys.render.time", renderTime.fullPath)
        assertEquals("2026-04-03T08:30:00Z", renderTime.mockValue)
    }

    @Test
    fun `fullPath includes sys prefix`() {
        val descriptor = SystemParameterDescriptor(
            path = "pages.total",
            description = "Total page count",
            type = "integer",
            scope = SystemParamScope.GLOBAL,
        )
        assertEquals("sys.pages.total", descriptor.fullPath)
    }

    @Test
    fun `buildNestedMap creates nested structure from dot paths`() {
        val result = SystemParameterRegistry.buildNestedMap(
            mapOf("pages.current" to 3, "pages.total" to 10),
        )

        @Suppress("UNCHECKED_CAST")
        val pages = result["pages"] as Map<String, Any?>
        assertEquals(3, pages["current"])
        assertEquals(10, pages["total"])
    }

    @Test
    fun `buildNestedMap handles single-level key`() {
        val result = SystemParameterRegistry.buildNestedMap(mapOf("version" to "1.0"))
        assertEquals("1.0", result["version"])
    }

    @Test
    fun `buildNestedMap handles deeply nested paths`() {
        val result = SystemParameterRegistry.buildNestedMap(mapOf("a.b.c.d" to 42))

        @Suppress("UNCHECKED_CAST")
        val a = result["a"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val b = a["b"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val c = b["c"] as Map<String, Any?>
        assertEquals(42, c["d"])
    }

    @Test
    fun `buildNestedMap returns empty map for empty input`() {
        val result = SystemParameterRegistry.buildNestedMap(emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `buildPageParams creates pages current and total structure`() {
        val result = SystemParameterRegistry.buildPageParams(5, 10)

        @Suppress("UNCHECKED_CAST")
        val pages = result["pages"] as Map<String, Any?>
        assertEquals(5, pages["current"])
        assertEquals(10, pages["total"])
    }

    @Test
    fun `buildGlobalParams returns render time when no totalPages`() {
        val result = SystemParameterRegistry.buildGlobalParams()

        @Suppress("UNCHECKED_CAST")
        val render = result["render"] as Map<String, Any?>
        val time = render["time"] as String
        assertNotNull(time)
        val parsed = OffsetDateTime.parse(time, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        assertNotNull(parsed)
    }

    @Test
    fun `buildGlobalParams includes pages total when provided`() {
        val result = SystemParameterRegistry.buildGlobalParams(10)

        @Suppress("UNCHECKED_CAST")
        val pages = result["pages"] as Map<String, Any?>
        assertEquals(10, pages["total"])

        // render.time should still be present
        @Suppress("UNCHECKED_CAST")
        val render = result["render"] as Map<String, Any?>
        assertNotNull(render["time"])
    }
}
