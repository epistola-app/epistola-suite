import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.time.Duration

plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

group = "app.epistola"
version = findProperty("releaseVersion") as String? ?: "dev"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// The Spring Boot BOM (imported via io.spring.dependency-management in each module) manages
// org.jetbrains.kotlin:* and would otherwise override the Kotlin compiler/scripting artifacts on
// the Kotlin Gradle plugin's compiler classpath to the BOM's kotlin.version. When that diverges
// from our Kotlin plugin version the compiler/scripting libs mismatch and the build dies with an
// internal compiler error (ClassNotFoundException ...ExpectedLocationUtilKt). Pin the managed
// kotlin.version to OUR Kotlin version so the BOM never clobbers the compiler classpath.
val kotlinVersion =
    extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs").findVersion("kotlin").get().requiredVersion
extra["kotlin.version"] = kotlinVersion

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

configurations {
    named("compileOnly") {
        extendsFrom(configurations.getByName("annotationProcessor"))
    }
}

// A consumer's test task takes the producer's runtime classpath as an input, and
// Spring Boot's build-info.properties travels on it. Both apps exclude its
// build.time; this keeps a reappearing timestamp from invalidating every
// downstream test run regardless.
normalization {
    runtimeClasspath {
        metaInf {
            ignoreProperty("build.time")
        }
    }
}

/**
 * Caps how many test JVMs run at once across the whole build. Compilation and
 * ktlint stay fully parallel; only tasks holding a slot queue. A Spring +
 * Testcontainers test JVM saturates a machine long before Gradle's worker count
 * does (each one boots contexts on every core and runs its own Postgres), and on
 * a developer machine the IDE and the container VM compete for the same cores.
 */
abstract class TestJvmSlots : BuildService<BuildServiceParameters.None>

val testJvmSlots = gradle.sharedServices.registerIfAbsent("testJvmSlots", TestJvmSlots::class) {
    val cores = Runtime.getRuntime().availableProcessors()
    maxParallelUsages.set(
        providers.gradleProperty("testJvmSlots").map { it.toInt() }.orElse(
            // CI runners are dedicated, so Gradle's own worker limit is the right
            // cap there. Locally, one slot per three cores leaves room for the
            // JUnit threads inside each JVM and for everything else on the box.
            providers.environmentVariable("CI").map { cores }.orElse(maxOf(1, cores / 3)),
        ),
    )
}

// JUnit's class-level concurrency inside each test JVM. JUnit's default is one
// thread per core, which on a 10-core machine overruns the deliberately small
// per-context Hikari pools (apps:epistola: 8; a web test thread holds its own
// connection plus the one the request thread takes) and fails tests with
// "Could not open JDBC Connection". Four is what CI's 4-core runner has always
// run with; `-PtestParallelism=N` overrides. uiTest and perfTest own their own.
val testParallelism = providers.gradleProperty("testParallelism").orElse("4")

fun Test.capJUnitParallelism() {
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", testParallelism.get())
}

// Gradle starts ready tasks in project order, which is alphabetical, so the two
// longest test JVMs (epistola-core, then apps:epistola) start after a handful of
// short ones and finish last. Measured on CI (PR #896): core's test JVM ran for
// 246 s and started at 150 s, 50 s after the first test task, and the job ended
// when it did. Soft ordering: every other module's test task of the same name
// prefers to start after those two, so they get the first workers.
val longestTestProjects = listOf(":modules:epistola-core", ":apps:epistola")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    usesService(testJvmSlots)
    if (project.path !in longestTestProjects) {
        shouldRunAfter(longestTestProjects.map { "$it:$name" })
    }

    // Shared GC choice only. Heap and JIT flags are per task below: `jvmArgs`
    // appends, so a flag set here cannot be taken back by a task that needs the
    // opposite (uiTest thought it had dropped -XX:TieredStopAtLevel=1; it had not).
    jvmArgs("-XX:+UseParallelGC")

    // Cross-cutting test-run metrics (see modules/testing .../metrics). A JUnit
    // Platform listener + Spring context-boot counter write a per-task JSON report
    // here so test performance (wall time, context boots, tenant bootstraps, slowest
    // classes) is captured on every run and monitorable over time, not eyeballed.
    systemProperty(
        "epistola.test.metrics.outDir",
        layout.buildDirectory.dir("test-metrics").get().asFile.absolutePath,
    )
    systemProperty("epistola.test.metrics.label", "${project.name}-$name")

    testLogging {
        events("passed", "skipped", "failed")
    }
}

val testSourceSet = sourceSets.named("test")
val perfTestExplicitlyRequested =
    gradle.startParameter.taskNames.any { requested ->
        requested == "perfTest" || requested.endsWith(":perfTest")
    }

tasks.register<Test>("unitTest") {
    description = "Runs unit tests (no Spring context, no Docker required)"
    group = "verification"
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform { excludeTags("integration", "ui") }
    // Short-lived JVM: C1-only JIT buys startup and costs nothing here.
    jvmArgs("-XX:TieredStopAtLevel=1", "-Xms256m", "-Xmx512m")
    capJUnitParallelism()
    testLogging { events("passed", "skipped", "failed") }
    filter { isFailOnNoMatchingTests = false }
}

// Spring + Testcontainers JVMs. Measured on CI (PR #896): letting these JVMs run
// full tiered compilation with a 2g heap doubled every context boot on the
// 4-core runner (single-context modules went from 24-30 s to 52-61 s), because
// four JVMs' C2 compiler threads compete with the boots for the same cores. The
// C1-only cap and the 1g heap are the configuration whose numbers are known.
val springTestJvmArgs = listOf("-XX:TieredStopAtLevel=1", "-Xms256m", "-Xmx1g")

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests (Spring + Testcontainers, no browser)"
    group = "verification"
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
        excludeTags("ui", "perf", "stress")
    }
    jvmArgs(springTestJvmArgs)
    capJUnitParallelism()
    testLogging { events("passed", "skipped", "failed") }
    filter { isFailOnNoMatchingTests = false }
}

// UI tests boot a full Spring Boot context + a child Chromium per class. On the
// 2-core CI runner, uncapped JUnit class-level concurrency starves CPU and trips
// timeouts — the dominant flake driver. So UI tests run with a bigger heap, no
// tiered-compilation cap (they are long-lived; C2 helps steady-state), a hard
// task timeout to catch hangs, and serialized class execution (only ~6 classes;
// `-PuiParallelism=N` overrides for local speed). See docs/testing.md.
tasks.register<Test>("uiTest") {
    description = "Runs UI tests (Playwright + Spring + Testcontainers)"
    group = "verification"
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform { includeTags("ui") }
    jvmArgs("-XX:+UseParallelGC", "-Xms512m", "-Xmx2g")
    maxParallelForks = 1
    timeout.set(Duration.ofMinutes(5))
    val uiParallelism = (findProperty("uiParallelism") as String?) ?: "1"
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", uiParallelism)
    // Per-method timeout scoped to the uiTest JVM only (NOT the shared
    // junit-platform.properties — perfTest shares that file and legitimately runs
    // long). Converts a hung browser into a fast, trace-captured failure.
    systemProperty("junit.jupiter.execution.timeout.testable.method.default", "120s")
    testLogging { events("passed", "skipped", "failed") }
    filter { isFailOnNoMatchingTests = false }
}

// CRITICAL: `gradle build` → `check` → the catch-all `test` task, which by
// default runs *every* tagged test — including `@Tag("ui")` — under the generic
// uncapped-parallel config. That is exactly the #418 flake environment, and it
// silently bypassed all of uiTest's hardening on CI. Keep UI (and opt-in perf)
// tests OUT of `test`. The module that owns UI tests (apps:epistola) wires its
// hardened `uiTest` into `check` itself, so `gradle build` still covers UI
// end-to-end through the right task without forking an empty UI JVM in every
// other module. `stress` stays in `test` (so CI runs it) and out of the local
// `integrationTest` loop, which is what the tag was introduced for.
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("ui", "perf") }
    jvmArgs(springTestJvmArgs)
    capJUnitParallelism()
}

// Perf tests — opt-in via `@Tag("perf")`. Excluded from `integrationTest` so the
// regular IT cycle stays fast. Run on demand with `:perfTest --tests ...`.
// Bigger heap + longer per-test timeout because perf tests bulk-insert lots of
// rows and often stress one shared Testcontainers Postgres deliberately. Keep
// the task itself serialized; each perf case controls its own workload
// concurrency.
tasks.register<Test>("perfTest") {
    description = "Runs performance tests (Spring + Testcontainers, opt-in)"
    group = "verification"
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform { includeTags("perf") }
    jvmArgs("-XX:+UseParallelGC", "-Xms512m", "-Xmx2g")
    maxParallelForks = 1
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    timeout.set(Duration.ofMinutes(15))
    testLogging { events("passed", "skipped", "failed", "standardOut") }
    filter { isFailOnNoMatchingTests = false }
    enabled = perfTestExplicitlyRequested
}
