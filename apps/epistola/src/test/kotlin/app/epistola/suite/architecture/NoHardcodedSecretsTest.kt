package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Project config-hygiene gate: sensitive keys in committed `application*.yaml` /
 * `.properties` must not carry a hardcoded literal value — they must come from
 * `${ENV}` (or a Kubernetes Secret). Empty values and `${...}` placeholders pass.
 *
 * This deliberately does **not** try to be a general secret scanner — that job
 * belongs to **gitleaks** (`.husky/pre-commit` for staged changes, the *Secret
 * Scan* CI workflow for full history; config in `.gitleaks.toml`). gitleaks is
 * entropy/format-based, so it would happily ignore a low-entropy `password: admin`
 * or `client-secret: foo` committed into a production config; this test catches
 * exactly that policy violation. The two are complementary.
 *
 * A small allowlist of clearly-marked **dev/local** profile files may carry
 * throwaway secrets (same spirit as the committed `admin/admin` local users).
 */
class NoHardcodedSecretsTest {

    /** YAML/properties keys whose value must never be a committed literal (outside dev/local files). */
    private val sensitiveKey = Regex(
        """^(password|client-?secret|material|private-?key|secret-?key|access-?key|api-?key|passphrase)$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Dev/local config files permitted to carry throwaway, non-production secrets.
     * Each is profile-scoped to local development and clearly marked as such.
     */
    private val allowedConfigFiles = setOf(
        "apps/epistola/src/main/resources/application-local.yaml",
        "apps/epistola/src/main/resources/application-localauth.yaml",
        "apps/epistola/src/main/resources/application-keycloak.yaml",
        "apps/epistola/src/main/resources/application-demo.yaml",
    )

    @Test
    fun `committed config sources secrets from the environment, not literals`() {
        val violations = mutableListOf<String>()

        for (path in RepoSources.configFiles()) {
            val relative = RepoSources.relativize(path)
            if (relative in allowedConfigFiles || "/src/test/" in relative) continue

            Files.readString(path).lineSequence().forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                val colon = line.indexOf(':')
                if (colon <= 0) return@forEachIndexed
                val key = line.substring(0, colon).trim().trimStart('-', ' ').trim()
                val value = line.substring(colon + 1).trim().trim('"', '\'')
                if (value.isEmpty() || value.startsWith("\${") || value.startsWith("[") || value.startsWith("{")) return@forEachIndexed
                if (sensitiveKey.matches(key)) {
                    violations += "$relative:${index + 1}: hardcoded '$key' value (use \${ENV} or a Secret)"
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Hardcoded secret value(s) in committed config. Source them from \${ENV} / a Secret " +
                "(see docs/encryption.md), or — for a genuine dev/local-only value — add the file to " +
                "allowedConfigFiles with a documented reason:\n${violations.joinToString("\n")}",
        )
    }
}
