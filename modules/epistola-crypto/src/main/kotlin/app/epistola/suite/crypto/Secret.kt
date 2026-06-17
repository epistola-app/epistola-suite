package app.epistola.suite.crypto

/**
 * A credential value that is **encrypted at rest** but held in **plaintext** in
 * memory. The plaintext lives in [value]; encryption happens only at the
 * database boundary (see the JDBI `SecretArgumentFactory` / `SecretColumnMapper`
 * in `epistola-core`).
 *
 * [toString] is redacted so a `Secret` can never leak its plaintext into logs,
 * stack traces, or `data class` renderings of enclosing objects. Read the
 * plaintext explicitly via [value] only at the point of use (e.g. when building
 * an outbound auth header).
 */
data class Secret(val value: String) {
    override fun toString(): String = "Secret(***)"
}
