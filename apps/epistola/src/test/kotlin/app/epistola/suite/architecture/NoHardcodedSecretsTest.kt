package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Build-time guard: committed source must not contain real credentials.
 *
 * Two rules:
 *  - **Config secrets** — in `application*.yaml` / `*.properties`, a sensitive key
 *    (password, client-secret, the encryption key `material`, …) must not carry a
 *    hardcoded literal value. Empty values and `${ENV}` placeholders are fine.
 *    A small allowlist of **dev/local** config files may carry throwaway secrets
 *    (the same spirit as the committed `admin/admin` local users) — anything
 *    outside that list (notably the base `application.yaml` and `prod` profile)
 *    must source secrets from the environment.
 *  - **Always-banned patterns** — PEM private-key blocks and AWS access-key ids
 *    must never appear in any committed source, even dev/test.
 *
 * Real secrets belong in environment variables / Kubernetes Secrets (see
 * `docs/encryption.md` and the Helm chart), never in the repo.
 */
class NoHardcodedSecretsTest {

    /** YAML/properties keys whose value must never be a committed literal (outside dev/local files). */
    private val sensitiveKey = Regex("""^(password|client-?secret|material|private-?key|secret-?key|access-?key|api-?key|passphrase)$""", RegexOption.IGNORE_CASE)

    /** Patterns that must never appear in ANY committed source. */
    private val alwaysBanned = mapOf(
        "PEM private key" to Regex("-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----"),
        "AWS access key id" to Regex("""\bAKIA[0-9A-Z]{16}\b"""),
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
    fun `committed config has no hardcoded secrets`() {
        val violations = mutableListOf<String>()

        for (path in RepoSources.configFiles()) {
            val relative = RepoSources.relativize(path)
            val devOrTest = relative in allowedConfigFiles || "/src/test/" in relative
            Files.readString(path).lineSequence().forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                alwaysBanned.forEach { (label, pattern) ->
                    if (pattern.containsMatchIn(raw)) violations += "$relative:${index + 1}: $label"
                }
                if (devOrTest) return@forEachIndexed

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

        // PEM/AWS patterns are banned in Kotlin sources too.
        for (path in RepoSources.mainKotlinFiles() + testKotlinFiles()) {
            val relative = RepoSources.relativize(path)
            val text = Files.readString(path)
            alwaysBanned.forEach { (label, pattern) ->
                if (pattern.containsMatchIn(text)) violations += "$relative: $label"
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Hardcoded credential(s) found in committed source. Move real secrets to env/Secret " +
                "(see docs/encryption.md), or — for a genuine dev/local-only value — add the file to " +
                "allowedConfigFiles with a documented reason:\n${violations.joinToString("\n")}",
        )
    }

    private fun testKotlinFiles(): List<Path> = listOf("apps", "modules")
        .map(RepoSources.repoRoot::resolve)
        .filter(Files::exists)
        .flatMap { base ->
            Files.walk(base).use { stream ->
                stream
                    .filter { it.fileName.toString().endsWith(".kt") }
                    .filter { p ->
                        val r = RepoSources.relativize(p)
                        "/src/test/" in r && "/build/" !in r
                    }
                    .toList()
            }
        }
}
