package app.epistola.suite.templates.validation

import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class PlaceholderValidatorTest {

    private val validator = PlaceholderValidator()

    private fun doc(
        vararg nodes: Pair<String, Node>,
        slots: Map<String, Slot>,
        root: String = "root",
    ): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = root,
        nodes = nodes.toMap(),
        slots = slots,
        themeRef = ThemeRef.Inherit,
    )

    private fun placeholder(id: String, name: String, fillSlotId: String, props: Map<String, Any?> = mapOf("name" to name)): Node = Node(id = id, type = "placeholder", slots = listOf(fillSlotId), props = props)

    private fun stencil(id: String, stencilId: String, slotId: String, extraProps: Map<String, Any?> = emptyMap()): Node = Node(
        id = id,
        type = "stencil",
        slots = listOf(slotId),
        props = mapOf("stencilId" to stencilId, "version" to 1) + extraProps,
    )

    private fun rootWith(children: List<String>, slotId: String = "root-slot"): Pair<Map<String, Node>, Map<String, Slot>> {
        val root = Node(id = "root", type = "root", slots = listOf(slotId))
        val slot = Slot(id = slotId, nodeId = "root", name = "children", children = children)
        return mapOf("root" to root) to mapOf(slotId to slot)
    }

    // ---------- name uniqueness ----------

    @Test
    fun `unique placeholder names pass`() {
        val (nodes, slots) = rootWith(listOf("ph1", "ph2"))
        val full = doc(
            *nodes.toList().toTypedArray(),
            "ph1" to placeholder("ph1", "header", "ph1-slot"),
            "ph2" to placeholder("ph2", "footer", "ph2-slot"),
            slots = slots + mapOf(
                "ph1-slot" to Slot("ph1-slot", "ph1", "fill", emptyList()),
                "ph2-slot" to Slot("ph2-slot", "ph2", "fill", emptyList()),
            ),
        )
        validator.validatePlaceholderNamesUnique(full)
    }

    @Test
    fun `duplicate placeholder names rejected`() {
        val (nodes, slots) = rootWith(listOf("ph1", "ph2"))
        val full = doc(
            *nodes.toList().toTypedArray(),
            "ph1" to placeholder("ph1", "body", "ph1-slot"),
            "ph2" to placeholder("ph2", "body", "ph2-slot"),
            slots = slots + mapOf(
                "ph1-slot" to Slot("ph1-slot", "ph1", "fill", emptyList()),
                "ph2-slot" to Slot("ph2-slot", "ph2", "fill", emptyList()),
            ),
        )
        assertThatThrownBy { validator.validatePlaceholderNamesUnique(full) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("PLACEHOLDER_NAME_DUPLICATE")
    }

    // ---------- per-stencil name uniqueness (template-level) ----------

    @Test
    fun `same placeholder name in two different stencil instances allowed in template`() {
        // root → [stencil-a → ph-a (body), stencil-b → ph-b (body)]
        val full = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "stencil-a" to stencil("stencil-a", "sten-A", "stencil-a-slot"),
            "stencil-b" to stencil("stencil-b", "sten-B", "stencil-b-slot"),
            "ph-a" to placeholder("ph-a", "body", "ph-a-fill"),
            "ph-b" to placeholder("ph-b", "body", "ph-b-fill"),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("stencil-a", "stencil-b")),
                "stencil-a-slot" to Slot("stencil-a-slot", "stencil-a", "children", listOf("ph-a")),
                "stencil-b-slot" to Slot("stencil-b-slot", "stencil-b", "children", listOf("ph-b")),
                "ph-a-fill" to Slot("ph-a-fill", "ph-a", "fill", emptyList()),
                "ph-b-fill" to Slot("ph-b-fill", "ph-b", "fill", emptyList()),
            ),
        )
        validator.validatePlaceholderNamesUniquePerStencil(full)
    }

    @Test
    fun `duplicate placeholder names within the same stencil rejected in template`() {
        // root → stencil → [ph1 (body), ph2 (body)]
        val full = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "stencil-a" to stencil("stencil-a", "sten-A", "stencil-a-slot"),
            "ph1" to placeholder("ph1", "body", "ph1-fill"),
            "ph2" to placeholder("ph2", "body", "ph2-fill"),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("stencil-a")),
                "stencil-a-slot" to Slot("stencil-a-slot", "stencil-a", "children", listOf("ph1", "ph2")),
                "ph1-fill" to Slot("ph1-fill", "ph1", "fill", emptyList()),
                "ph2-fill" to Slot("ph2-fill", "ph2", "fill", emptyList()),
            ),
        )
        assertThatThrownBy { validator.validatePlaceholderNamesUniquePerStencil(full) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("PLACEHOLDER_NAME_DUPLICATE")
    }

    // ---------- name slug ----------

    @Test
    fun `slug names accepted`() {
        val (nodes, slots) = rootWith(listOf("ph1"))
        val full = doc(
            *nodes.toList().toTypedArray(),
            "ph1" to placeholder("ph1", "page-header", "ph1-slot"),
            slots = slots + mapOf("ph1-slot" to Slot("ph1-slot", "ph1", "fill", emptyList())),
        )
        validator.validatePlaceholderNameSlug(full)
    }

    @Test
    fun `non-slug names rejected`() {
        val cases = listOf("Header", "page header", "page_header", "1page", "-leading", "")
        for (name in cases) {
            val (nodes, slots) = rootWith(listOf("ph1"))
            val full = doc(
                *nodes.toList().toTypedArray(),
                "ph1" to placeholder("ph1", name, "ph1-slot"),
                slots = slots + mapOf("ph1-slot" to Slot("ph1-slot", "ph1", "fill", emptyList())),
            )
            assertThatThrownBy { validator.validatePlaceholderNameSlug(full) }
                .`as`("name '%s' should be rejected", name)
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("PLACEHOLDER_NAME_INVALID")
        }
    }

    @Test
    fun `placeholder without name rejected`() {
        val (nodes, slots) = rootWith(listOf("ph1"))
        val full = doc(
            *nodes.toList().toTypedArray(),
            "ph1" to Node(id = "ph1", type = "placeholder", slots = listOf("ph1-slot"), props = emptyMap()),
            slots = slots + mapOf("ph1-slot" to Slot("ph1-slot", "ph1", "fill", emptyList())),
        )
        assertThatThrownBy { validator.validatePlaceholderNameSlug(full) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("PLACEHOLDER_NAME_INVALID")
    }

    // ---------- nested placeholder definition ----------

    @Test
    fun `placeholder inside placeholder fill at definition rejected`() {
        // root → outer-ph → fill → inner-ph
        val full = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "outer" to placeholder("outer", "outer", "outer-fill"),
            "inner" to placeholder("inner", "inner", "inner-fill"),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("outer")),
                "outer-fill" to Slot("outer-fill", "outer", "fill", listOf("inner")),
                "inner-fill" to Slot("inner-fill", "inner", "fill", emptyList()),
            ),
        )
        assertThatThrownBy { validator.validateNoNestedPlaceholderDefinition(full) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("PLACEHOLDER_NESTED_DEFINITION")
    }

    @Test
    fun `siblings are not nested`() {
        val full = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "ph1" to placeholder("ph1", "a", "ph1-slot"),
            "ph2" to placeholder("ph2", "b", "ph2-slot"),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("ph1", "ph2")),
                "ph1-slot" to Slot("ph1-slot", "ph1", "fill", emptyList()),
                "ph2-slot" to Slot("ph2-slot", "ph2", "fill", emptyList()),
            ),
        )
        validator.validateNoNestedPlaceholderDefinition(full)
    }

    // ---------- placeholder must have stencil ancestor (template context) ----------

    @Test
    fun `placeholder under stencil accepted`() {
        val full = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "stencil-a" to stencil("stencil-a", "header-stencil", "stencil-a-slot"),
            "ph" to placeholder("ph", "body", "ph-slot"),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("stencil-a")),
                "stencil-a-slot" to Slot("stencil-a-slot", "stencil-a", "children", listOf("ph")),
                "ph-slot" to Slot("ph-slot", "ph", "fill", emptyList()),
            ),
        )
        validator.validatePlaceholdersHaveStencilAncestor(full)
    }

    @Test
    fun `placeholder without stencil ancestor rejected`() {
        val full = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "ph" to placeholder("ph", "body", "ph-slot"),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("ph")),
                "ph-slot" to Slot("ph-slot", "ph", "fill", emptyList()),
            ),
        )
        assertThatThrownBy { validator.validatePlaceholdersHaveStencilAncestor(full) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("PLACEHOLDER_OUTSIDE_STENCIL")
    }

    // ---------- recursion ----------

    @Test
    fun `direct stencil recursion rejected`() {
        // root → stencil A → fill slot → stencil A
        val full = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "outer-A" to stencil("outer-A", "letter", "outer-slot"),
            "inner-A" to stencil("inner-A", "letter", "inner-slot"),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("outer-A")),
                "outer-slot" to Slot("outer-slot", "outer-A", "children", listOf("inner-A")),
                "inner-slot" to Slot("inner-slot", "inner-A", "children", emptyList()),
            ),
        )
        assertThatThrownBy { validator.validateNoStencilRecursion(full) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("STENCIL_RECURSION")
    }

    @Test
    fun `deep stencil recursion through placeholder rejected`() {
        // root → A → ph → B → ph → A
        val full = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "A1" to stencil("A1", "stencil-A", "A1-slot"),
            "p1" to placeholder("p1", "ph", "p1-fill"),
            "B" to stencil("B", "stencil-B", "B-slot"),
            "p2" to placeholder("p2", "inner", "p2-fill"),
            "A2" to stencil("A2", "stencil-A", "A2-slot"),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("A1")),
                "A1-slot" to Slot("A1-slot", "A1", "children", listOf("p1")),
                "p1-fill" to Slot("p1-fill", "p1", "fill", listOf("B")),
                "B-slot" to Slot("B-slot", "B", "children", listOf("p2")),
                "p2-fill" to Slot("p2-fill", "p2", "fill", listOf("A2")),
                "A2-slot" to Slot("A2-slot", "A2", "children", emptyList()),
            ),
        )
        assertThatThrownBy { validator.validateNoStencilRecursion(full) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("STENCIL_RECURSION")
    }

    @Test
    fun `multiple unrelated instances of same stencil accepted`() {
        // Two siblings, same stencilId, but not nested
        val full = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "A1" to stencil("A1", "letter", "A1-slot"),
            "A2" to stencil("A2", "letter", "A2-slot"),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("A1", "A2")),
                "A1-slot" to Slot("A1-slot", "A1", "children", emptyList()),
                "A2-slot" to Slot("A2-slot", "A2", "children", emptyList()),
            ),
        )
        validator.validateNoStencilRecursion(full)
    }

    // ---------- forward-compat reservations ----------

    @Test
    fun `parameterBindings on stencil rejected`() {
        val full = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "s1" to stencil(
                "s1",
                "header",
                "s1-slot",
                extraProps = mapOf("parameterBindings" to mapOf("date" to "today")),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("s1")),
                "s1-slot" to Slot("s1-slot", "s1", "children", emptyList()),
            ),
        )
        assertThatThrownBy { validator.validateForwardCompatReservations(full) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("STENCIL_PARAMETERBINDINGS_RESERVED")
    }

    // ---------- composite entry points ----------

    @Test
    fun `validateAsTemplate accepts a clean template`() {
        val full = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "stencil-a" to stencil("stencil-a", "header", "stencil-a-slot"),
            "ph" to placeholder("ph", "body", "ph-slot"),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("stencil-a")),
                "stencil-a-slot" to Slot("stencil-a-slot", "stencil-a", "children", listOf("ph")),
                "ph-slot" to Slot("ph-slot", "ph", "fill", emptyList()),
            ),
        )
        validator.validateAsTemplate(full)
    }

    @Test
    fun `validateAsStencilDefinition accepts a clean stencil`() {
        val full = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "ph" to placeholder("ph", "body", "ph-slot"),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("ph")),
                "ph-slot" to Slot("ph-slot", "ph", "fill", emptyList()),
            ),
        )
        validator.validateAsStencilDefinition(full)
    }

    @Test
    fun `non-placeholder non-stencil documents pass all checks`() {
        val full = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "text" to Node(id = "text", type = "text", slots = emptyList(), props = mapOf("content" to "hello")),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("text")),
            ),
        )
        validator.validateAsStencilDefinition(full)
        validator.validateAsTemplate(full)
        assertThat(validator).isNotNull
    }
}
