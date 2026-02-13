package app.epistola.generation.pdf

data class StyleCascadeFixture(
    val id: String,
    val description: String,
    val documentStyles: Map<String, Any>?,
    val ancestorStyles: List<Map<String, Any>>,
    val blockStyles: Map<String, Any>?,
    val expected: Map<String, Any>,
)

object StyleCascadeFixtures {
    val cases = listOf(
        StyleCascadeFixture(
            id = "doc_container_child_inherit_font_size",
            description = "Child block inherits font size from nearest ancestor when child does not define it.",
            documentStyles = mapOf(
                "fontSize" to "4rem",
                "color" to "#333333",
            ),
            ancestorStyles = listOf(
                mapOf("fontSize" to "2rem"),
            ),
            blockStyles = null,
            expected = mapOf(
                "fontSize" to "2rem",
                "color" to "#333333",
            ),
        ),
        StyleCascadeFixture(
            id = "child_override_wins",
            description = "Child inline style overrides inherited value from document and ancestor chain.",
            documentStyles = mapOf(
                "fontSize" to "4rem",
                "color" to "#333333",
            ),
            ancestorStyles = listOf(
                mapOf("fontSize" to "2rem"),
            ),
            blockStyles = mapOf(
                "fontSize" to "1rem",
            ),
            expected = mapOf(
                "fontSize" to "1rem",
                "color" to "#333333",
            ),
        ),
        StyleCascadeFixture(
            id = "deep_ancestor_chain",
            description = "Nearest ancestor value wins in deep hierarchy when block has no override.",
            documentStyles = mapOf("color" to "#333333"),
            ancestorStyles = listOf(
                mapOf("color" to "#222222"),
                mapOf("color" to "#111111"),
            ),
            blockStyles = null,
            expected = mapOf("color" to "#111111"),
        ),
        StyleCascadeFixture(
            id = "non_inheritable_not_propagated",
            description = "Non-inheritable keys (layout/spacing) do not flow from parent to child.",
            documentStyles = mapOf("fontSize" to "14px"),
            ancestorStyles = listOf(
                mapOf(
                    "paddingTop" to "12px",
                    "marginBottom" to "8px",
                ),
            ),
            blockStyles = null,
            expected = mapOf("fontSize" to "14px"),
        ),
        StyleCascadeFixture(
            id = "background_color_inherited",
            description = "Background color inherits through ancestors when child is unset.",
            documentStyles = mapOf("backgroundColor" to "#ffffff"),
            ancestorStyles = listOf(
                mapOf("backgroundColor" to "#ffeecc"),
            ),
            blockStyles = null,
            expected = mapOf("backgroundColor" to "#ffeecc"),
        ),
    )
}
