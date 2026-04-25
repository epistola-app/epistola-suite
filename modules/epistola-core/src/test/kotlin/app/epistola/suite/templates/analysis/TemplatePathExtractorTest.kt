package app.epistola.suite.templates.analysis

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TemplatePathExtractorTest {

    private lateinit var extractor: TemplatePathExtractor

    @BeforeEach
    fun setUp() {
        extractor = TemplatePathExtractor()
    }

    private fun emptyDocument(): TemplateDocument = TemplateDocument(
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("root-slot"))),
        slots = mapOf("root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children")),
        themeRef = ThemeRef.Inherit,
    )

    private fun documentWithChildren(vararg children: Pair<String, Node>): TemplateDocument {
        val childIds = children.map { it.first }
        val childSlots = children.flatMap { (_, node) ->
            node.slots.map { slotId ->
                slotId to Slot(id = slotId, nodeId = node.id, name = "children")
            }
        }
        return TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                *children,
            ),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = childIds),
                *childSlots.toTypedArray(),
            ),
            themeRef = ThemeRef.Inherit,
        )
    }

    @Nested
    inner class EmptyTemplate {
        @Test
        fun `empty template returns empty paths`() {
            val paths = extractor.extractReferencedPaths(emptyDocument())
            assertThat(paths).isEmpty()
        }
    }

    @Nested
    inner class TextExpressions {
        @Test
        fun `extracts inline expression from TipTap content`() {
            val doc = documentWithChildren(
                "text1" to Node(
                    id = "text1",
                    type = "text",
                    props = mapOf(
                        "content" to mapOf(
                            "type" to "doc",
                            "content" to listOf(
                                mapOf(
                                    "type" to "paragraph",
                                    "content" to listOf(
                                        mapOf("type" to "text", "text" to "Hello "),
                                        mapOf("type" to "expression", "attrs" to mapOf("expression" to "customer.name")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).containsExactly("customer.name")
        }

        @Test
        fun `extracts multiple inline expressions`() {
            val doc = documentWithChildren(
                "text1" to Node(
                    id = "text1",
                    type = "text",
                    props = mapOf(
                        "content" to mapOf(
                            "type" to "doc",
                            "content" to listOf(
                                mapOf(
                                    "type" to "paragraph",
                                    "content" to listOf(
                                        mapOf("type" to "expression", "attrs" to mapOf("expression" to "customer.name")),
                                        mapOf("type" to "expression", "attrs" to mapOf("expression" to "customer.email")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).containsExactlyInAnyOrder("customer.name", "customer.email")
        }
    }

    @Nested
    inner class ConditionalExpressions {
        @Test
        fun `extracts paths from conditional`() {
            val doc = documentWithChildren(
                "cond1" to Node(
                    id = "cond1",
                    type = "conditional",
                    props = mapOf(
                        "condition" to mapOf("raw" to "order.status", "language" to "simple_path"),
                    ),
                ),
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).containsExactly("order.status")
        }

        @Test
        fun `extracts paths from JSONata comparison`() {
            val doc = documentWithChildren(
                "cond1" to Node(
                    id = "cond1",
                    type = "conditional",
                    props = mapOf(
                        "condition" to mapOf("raw" to "order.status = 'active'", "language" to "jsonata"),
                    ),
                ),
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).contains("order.status")
            assertThat(paths).doesNotContain("active") // string literal, not a path
        }
    }

    @Nested
    inner class QrCodeExpressions {
        @Test
        fun `extracts path from qrcode value`() {
            val doc = documentWithChildren(
                "qr1" to Node(
                    id = "qr1",
                    type = "qrcode",
                    props = mapOf(
                        "value" to mapOf("raw" to "customer.id", "language" to "simple_path"),
                    ),
                ),
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).containsExactly("customer.id")
        }
    }

    @Nested
    inner class LoopExpressions {
        @Test
        fun `extracts loop source and resolves child paths`() {
            val doc = TemplateDocument(
                root = "root",
                nodes = mapOf(
                    "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                    "loop1" to Node(
                        id = "loop1",
                        type = "loop",
                        slots = listOf("loop-body"),
                        props = mapOf(
                            "expression" to mapOf("raw" to "orders", "language" to "jsonata"),
                            "itemAlias" to "order",
                        ),
                    ),
                    "text1" to Node(
                        id = "text1",
                        type = "text",
                        props = mapOf(
                            "content" to mapOf(
                                "type" to "doc",
                                "content" to listOf(
                                    mapOf(
                                        "type" to "paragraph",
                                        "content" to listOf(
                                            mapOf("type" to "expression", "attrs" to mapOf("expression" to "order.name")),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                slots = mapOf(
                    "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("loop1")),
                    "loop-body" to Slot(id = "loop-body", nodeId = "loop1", name = "body", children = listOf("text1")),
                ),
                themeRef = ThemeRef.Inherit,
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).containsExactlyInAnyOrder("orders", "orders[*].name")
        }

        @Test
        fun `resolves nested loops`() {
            val doc = TemplateDocument(
                root = "root",
                nodes = mapOf(
                    "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                    "loop1" to Node(
                        id = "loop1",
                        type = "loop",
                        slots = listOf("loop1-body"),
                        props = mapOf(
                            "expression" to mapOf("raw" to "orders", "language" to "jsonata"),
                            "itemAlias" to "order",
                        ),
                    ),
                    "loop2" to Node(
                        id = "loop2",
                        type = "loop",
                        slots = listOf("loop2-body"),
                        props = mapOf(
                            "expression" to mapOf("raw" to "order.items", "language" to "jsonata"),
                            "itemAlias" to "lineItem",
                        ),
                    ),
                    "text1" to Node(
                        id = "text1",
                        type = "text",
                        props = mapOf(
                            "content" to mapOf(
                                "type" to "doc",
                                "content" to listOf(
                                    mapOf(
                                        "type" to "paragraph",
                                        "content" to listOf(
                                            mapOf("type" to "expression", "attrs" to mapOf("expression" to "lineItem.price")),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                slots = mapOf(
                    "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("loop1")),
                    "loop1-body" to Slot(id = "loop1-body", nodeId = "loop1", name = "body", children = listOf("loop2")),
                    "loop2-body" to Slot(id = "loop2-body", nodeId = "loop2", name = "body", children = listOf("text1")),
                ),
                themeRef = ThemeRef.Inherit,
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).containsExactlyInAnyOrder(
                "orders",
                "orders[*].items",
                "orders[*].items[*].price",
            )
        }

        @Test
        fun `uses default item alias when not specified`() {
            val doc = TemplateDocument(
                root = "root",
                nodes = mapOf(
                    "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                    "loop1" to Node(
                        id = "loop1",
                        type = "loop",
                        slots = listOf("loop-body"),
                        props = mapOf(
                            "expression" to mapOf("raw" to "items", "language" to "jsonata"),
                            // No itemAlias → defaults to "item"
                        ),
                    ),
                    "text1" to Node(
                        id = "text1",
                        type = "text",
                        props = mapOf(
                            "content" to mapOf(
                                "type" to "doc",
                                "content" to listOf(
                                    mapOf(
                                        "type" to "paragraph",
                                        "content" to listOf(
                                            mapOf("type" to "expression", "attrs" to mapOf("expression" to "item.name")),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                slots = mapOf(
                    "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("loop1")),
                    "loop-body" to Slot(id = "loop-body", nodeId = "loop1", name = "body", children = listOf("text1")),
                ),
                themeRef = ThemeRef.Inherit,
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).containsExactlyInAnyOrder("items", "items[*].name")
        }

        @Test
        fun `datalist works same as loop`() {
            val doc = documentWithChildren(
                "dl1" to Node(
                    id = "dl1",
                    type = "datalist",
                    props = mapOf(
                        "expression" to mapOf("raw" to "products", "language" to "jsonata"),
                        "itemAlias" to "product",
                    ),
                ),
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).containsExactly("products")
        }
    }

    @Nested
    inner class Exclusions {
        @Test
        fun `excludes system variables`() {
            val doc = documentWithChildren(
                "text1" to Node(
                    id = "text1",
                    type = "text",
                    props = mapOf(
                        "content" to mapOf(
                            "type" to "doc",
                            "content" to listOf(
                                mapOf(
                                    "type" to "paragraph",
                                    "content" to listOf(
                                        mapOf("type" to "expression", "attrs" to mapOf("expression" to "sys.page.number")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).isEmpty()
        }

        @Test
        fun `excludes loop auto-variables`() {
            val doc = TemplateDocument(
                root = "root",
                nodes = mapOf(
                    "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                    "loop1" to Node(
                        id = "loop1",
                        type = "loop",
                        slots = listOf("loop-body"),
                        props = mapOf(
                            "expression" to mapOf("raw" to "items", "language" to "jsonata"),
                            "itemAlias" to "item",
                        ),
                    ),
                    "cond1" to Node(
                        id = "cond1",
                        type = "conditional",
                        props = mapOf(
                            "condition" to mapOf("raw" to "item_first", "language" to "simple_path"),
                        ),
                    ),
                ),
                slots = mapOf(
                    "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("loop1")),
                    "loop-body" to Slot(id = "loop-body", nodeId = "loop1", name = "body", children = listOf("cond1")),
                ),
                themeRef = ThemeRef.Inherit,
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).containsExactly("items") // item_first excluded
        }

        @Test
        fun `excludes keywords`() {
            val doc = documentWithChildren(
                "cond1" to Node(
                    id = "cond1",
                    type = "conditional",
                    props = mapOf(
                        "condition" to mapOf("raw" to "true", "language" to "jsonata"),
                    ),
                ),
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).isEmpty()
        }
    }

    @Nested
    inner class JSONataExpressions {
        @Test
        fun `extracts paths from JSONata function calls`() {
            val doc = documentWithChildren(
                "text1" to Node(
                    id = "text1",
                    type = "text",
                    props = mapOf(
                        "content" to mapOf(
                            "type" to "doc",
                            "content" to listOf(
                                mapOf(
                                    "type" to "paragraph",
                                    "content" to listOf(
                                        mapOf("type" to "expression", "attrs" to mapOf("expression" to "\$sum(items.price)")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).containsExactly("items.price")
            assertThat(paths).doesNotContain("\$sum") // function excluded
        }

        @Test
        fun `extracts paths from string concatenation`() {
            val doc = documentWithChildren(
                "text1" to Node(
                    id = "text1",
                    type = "text",
                    props = mapOf(
                        "content" to mapOf(
                            "type" to "doc",
                            "content" to listOf(
                                mapOf(
                                    "type" to "paragraph",
                                    "content" to listOf(
                                        mapOf("type" to "expression", "attrs" to mapOf("expression" to "first & ' ' & last")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).containsExactlyInAnyOrder("first", "last")
        }
    }

    @Nested
    inner class RootLevelPaths {
        @Test
        fun `paths outside loops stay at root level`() {
            val doc = documentWithChildren(
                "text1" to Node(
                    id = "text1",
                    type = "text",
                    props = mapOf(
                        "content" to mapOf(
                            "type" to "doc",
                            "content" to listOf(
                                mapOf(
                                    "type" to "paragraph",
                                    "content" to listOf(
                                        mapOf("type" to "expression", "attrs" to mapOf("expression" to "company.name")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val paths = extractor.extractReferencedPaths(doc)
            assertThat(paths).containsExactly("company.name")
        }
    }
}
