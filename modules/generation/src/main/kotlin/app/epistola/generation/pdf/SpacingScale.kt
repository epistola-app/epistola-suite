package app.epistola.generation.pdf

/**
 * Systematic spacing scale based on multiples of a configurable base unit.
 *
 * The scale uses `sp(N)` notation where N is a multiplier applied to the base unit.
 * For example, with the default base unit of 4pt:
 * - `sp(2)` resolves to 8pt
 * - `sp(3)` resolves to 12pt
 * - `sp(0.5)` resolves to 2pt
 *
 * The scale is intentionally non-linear to provide fine control at small sizes
 * and larger jumps at bigger sizes, matching the perceptual needs of document layout.
 *
 * Arbitrary multipliers (e.g., `sp(2.5)`) are supported as an escape hatch
 * while still staying on-grid relative to the base unit.
 */
object SpacingScale {

    /** Default base unit in points. 4pt divides cleanly into 72pt/inch (18 steps). */
    const val DEFAULT_BASE_UNIT = 4f

    /**
     * Named scale steps: multiplier name → multiplier value.
     *
     * These are the "blessed" steps that appear in the editor UI.
     * The scale is non-linear: fine-grained at small values, coarser at large values.
     */
    val STEPS: LinkedHashMap<String, Float> = linkedMapOf(
        "0" to 0f,
        "0.5" to 0.5f,
        "1" to 1f,
        "1.5" to 1.5f,
        "2" to 2f,
        "3" to 3f,
        "4" to 4f,
        "5" to 5f,
        "6" to 6f,
        "8" to 8f,
        "10" to 10f,
        "12" to 12f,
        "16" to 16f,
    )

    /**
     * Resolves a scale step to an absolute pt value.
     *
     * @param step The scale step name (e.g., "2", "0.5") or an arbitrary numeric multiplier
     * @param baseUnit The base unit in points (default: [DEFAULT_BASE_UNIT])
     * @return The resolved value in points
     */
    fun resolve(step: String, baseUnit: Float = DEFAULT_BASE_UNIT): Float {
        val multiplier = STEPS[step] ?: step.toFloatOrNull() ?: return 0f
        return multiplier * baseUnit
    }

    /**
     * Parses an `sp(N)` token string to an absolute pt value.
     *
     * @param value The string value, e.g., `"sp(3)"` or `"sp(0.5)"`
     * @param baseUnit The base unit in points (default: [DEFAULT_BASE_UNIT])
     * @return The resolved pt value, or null if the string is not an `sp()` token
     */
    fun parseSp(value: String, baseUnit: Float = DEFAULT_BASE_UNIT): Float? {
        val match = SP_PATTERN.matchEntire(value) ?: return null
        val step = match.groupValues[1]
        return resolve(step, baseUnit)
    }

    private val SP_PATTERN = Regex("""sp\(([^)]+)\)""")
}
