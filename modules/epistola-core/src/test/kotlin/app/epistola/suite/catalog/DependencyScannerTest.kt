package app.epistola.suite.catalog

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefInherit
import app.epistola.template.model.ThemeRefOverride
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DependencyScannerTest {

    @Test
    fun `scan finds theme ref`() {
        val doc = TemplateDocument(
            modelVersion = 1,
            root = "n-root",
            themeRef = ThemeRefOverride(themeId = "corporate"),
            nodes = mapOf("n-root" to Node(id = "n-root", type = "root", slots = emptyList())),
            slots = emptyMap(),
        )

        val deps = DependencyScanner.scan(doc)
        assertThat(deps.themeRefs).containsExactly("corporate")
    }

    @Test
    fun `scan ignores inherit theme ref`() {
        val doc = TemplateDocument(
            modelVersion = 1,
            root = "n-root",
            themeRef = ThemeRefInherit(),
            nodes = mapOf("n-root" to Node(id = "n-root", type = "root", slots = emptyList())),
            slots = emptyMap(),
        )

        val deps = DependencyScanner.scan(doc)
        assertThat(deps.themeRefs).isEmpty()
    }

    @Test
    fun `scan finds stencil refs`() {
        val doc = TemplateDocument(
            modelVersion = 1,
            root = "n-root",
            nodes = mapOf(
                "n-root" to Node(id = "n-root", type = "root", slots = listOf("s-children")),
                "n-stencil" to Node(
                    id = "n-stencil",
                    type = "stencil",
                    slots = emptyList(),
                    props = mapOf("stencilId" to "company-header", "version" to 1),
                ),
            ),
            slots = mapOf("s-children" to Slot(id = "s-children", nodeId = "n-root", name = "children", children = listOf("n-stencil"))),
        )

        val deps = DependencyScanner.scan(doc)
        assertThat(deps.stencilRefs).containsExactly("company-header")
    }

    @Test
    fun `scan finds image asset refs`() {
        val doc = TemplateDocument(
            modelVersion = 1,
            root = "n-root",
            nodes = mapOf(
                "n-root" to Node(id = "n-root", type = "root", slots = listOf("s-children")),
                "n-img" to Node(
                    id = "n-img",
                    type = "image",
                    slots = emptyList(),
                    props = mapOf("assetId" to "abc-123"),
                ),
            ),
            slots = mapOf("s-children" to Slot(id = "s-children", nodeId = "n-root", name = "children", children = listOf("n-img"))),
        )

        val deps = DependencyScanner.scan(doc)
        assertThat(deps.assetRefs).containsExactly("abc-123")
    }

    @Test
    fun `scan with variant attributes includes them`() {
        val doc = TemplateDocument(
            modelVersion = 1,
            root = "n-root",
            nodes = mapOf("n-root" to Node(id = "n-root", type = "root", slots = emptyList())),
            slots = emptyMap(),
        )

        val deps = DependencyScanner.scan(doc, setOf("language", "brand"))
        assertThat(deps.attributeKeys).containsExactlyInAnyOrder("language", "brand")
    }

    @Test
    fun `merge combines all dependency sets`() {
        val a = DependencyScanner.Dependencies(themeRefs = setOf("t1"), stencilRefs = setOf("s1"))
        val b = DependencyScanner.Dependencies(themeRefs = setOf("t2"), assetRefs = setOf("a1"))

        val merged = DependencyScanner.merge(a, b)
        assertThat(merged.themeRefs).containsExactlyInAnyOrder("t1", "t2")
        assertThat(merged.stencilRefs).containsExactly("s1")
        assertThat(merged.assetRefs).containsExactly("a1")
    }

    @Test
    fun `scan empty document returns empty deps`() {
        val doc = TemplateDocument(
            modelVersion = 1,
            root = "n-root",
            nodes = mapOf("n-root" to Node(id = "n-root", type = "root", slots = emptyList())),
            slots = emptyMap(),
        )

        val deps = DependencyScanner.scan(doc)
        assertThat(deps.themeRefs).isEmpty()
        assertThat(deps.stencilRefs).isEmpty()
        assertThat(deps.assetRefs).isEmpty()
        assertThat(deps.attributeKeys).isEmpty()
    }
}
