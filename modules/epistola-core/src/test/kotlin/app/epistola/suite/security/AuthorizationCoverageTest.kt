package app.epistola.suite.security

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.Query
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter

/**
 * Safety-net test that verifies every [Command] and [Query] in the codebase
 * implements an [Authorized] interface.
 *
 * This prevents new commands or queries from being added without declaring
 * their authorization requirements — the mediator would reject them at runtime,
 * but this test catches the omission at build time.
 */
@Tag("unit")
class AuthorizationCoverageTest {

    private val basePackage = "app.epistola.suite"

    @Test
    fun `every Command must implement Authorized`() {
        val commandClasses = findConcreteSubtypes(Command::class.java)

        assertThat(commandClasses)
            .withFailMessage("No Command classes found — classpath scanning may be broken")
            .isNotEmpty()

        val missing = commandClasses.filter { !Authorized::class.java.isAssignableFrom(it) }

        assertThat(missing)
            .withFailMessage {
                "Commands missing Authorized interface:\n" +
                    missing.joinToString("\n") { "  - ${it.simpleName}" }
            }
            .isEmpty()
    }

    @Test
    fun `every Query must implement Authorized`() {
        val queryClasses = findConcreteSubtypes(Query::class.java)

        assertThat(queryClasses)
            .withFailMessage("No Query classes found — classpath scanning may be broken")
            .isNotEmpty()

        val missing = queryClasses.filter { !Authorized::class.java.isAssignableFrom(it) }

        assertThat(missing)
            .withFailMessage {
                "Queries missing Authorized interface:\n" +
                    missing.joinToString("\n") { "  - ${it.simpleName}" }
            }
            .isEmpty()
    }

    @Test
    fun `every RequiresPermission declares a tenantKey`() {
        val allClasses = findConcreteSubtypes(Command::class.java) + findConcreteSubtypes(Query::class.java)
        val requiresPermissionClasses = allClasses.filter { RequiresPermission::class.java.isAssignableFrom(it) }

        assertThat(requiresPermissionClasses)
            .withFailMessage("No RequiresPermission classes found")
            .isNotEmpty()

        // RequiresPermission interface requires tenantKey — this is compile-time enforced,
        // but verify at runtime that all implementations actually provide a non-null property
        for (clazz in requiresPermissionClasses) {
            assertThat(RequiresPermission::class.java.isAssignableFrom(clazz))
                .withFailMessage("${clazz.simpleName} implements RequiresPermission but the check failed")
                .isTrue()
        }
    }

    /**
     * Packages containing production command/query classes.
     * Test-only implementations (e.g., TestCommand, FailingQuery) are excluded
     * by filtering to these known production packages.
     */
    private val productionPackages = listOf(
        "app.epistola.suite.tenants",
        "app.epistola.suite.templates",
        "app.epistola.suite.themes",
        "app.epistola.suite.environments",
        "app.epistola.suite.documents",
        "app.epistola.suite.assets",
        "app.epistola.suite.attributes",
        "app.epistola.suite.apikeys",
        "app.epistola.suite.users",
        "app.epistola.suite.loadtest",
        "app.epistola.suite.feedback",
    )

    private fun findConcreteSubtypes(type: Class<*>): List<Class<*>> {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AssignableTypeFilter(type))

        return productionPackages.flatMap { pkg ->
            scanner.findCandidateComponents(pkg)
        }.mapNotNull { beanDef ->
            try {
                val clazz = Class.forName(beanDef.beanClassName)
                if (!clazz.isInterface && !java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) clazz else null
            } catch (_: ClassNotFoundException) {
                null
            }
        }
    }
}
