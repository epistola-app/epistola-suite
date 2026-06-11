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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

configurations {
    named("compileOnly") {
        extendsFrom(configurations.getByName("annotationProcessor"))
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Baseline heap for the catch-all `test` task, which boots full Spring Boot
    // contexts (e.g. apps:epistola observability tests). 512m was too tight under
    // parallel forks on the 2-core CI runner and intermittently OOM'd while loading
    // a context (surfacing as a spurious PrometheusEndpointTest failure). 1g gives
    // headroom. unitTest/integrationTest/uiTest/perfTest re-declare their own heap.
    jvmArgs(
        "-XX:+UseParallelGC",
        "-XX:TieredStopAtLevel=1",
        "-Xms256m",
        "-Xmx1g",
    )

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
    jvmArgs("-XX:+UseParallelGC", "-XX:TieredStopAtLevel=1", "-Xms256m", "-Xmx512m")
    testLogging { events("passed", "skipped", "failed") }
    filter { isFailOnNoMatchingTests = false }
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests (Spring + Testcontainers, no browser)"
    group = "verification"
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
        excludeTags("ui", "perf")
    }
    // 1g, not 512m: JUnit class-level concurrency boots distinct Spring contexts in
    // parallel (one per unique @SpringBootTest config), and every context load runs a
    // full classpath component scan whose classfile metadata transiently costs
    // ~100MB+. On a 2-core CI runner at most 2 contexts load at once; on a 10-core
    // dev machine ~7 can, which intermittently OOMs a 512m heap with
    // "GC overhead limit exceeded" mid-scan and fails a random victim test.
    jvmArgs("-XX:+UseParallelGC", "-XX:TieredStopAtLevel=1", "-Xms256m", "-Xmx1g")
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
// 512m / uncapped-parallel config. That is exactly the #418 flake environment,
// and it silently bypassed all of uiTest's hardening on CI. Keep UI (and opt-in
// perf) tests OUT of `test`, and make `check` depend on the hardened `uiTest`
// so `gradle build` still covers UI end-to-end — through the right task.
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("ui", "perf") }
    // Same 1g headroom as integrationTest — this catch-all also boots Spring
    // contexts concurrently (see the integrationTest comment above).
    maxHeapSize = "1g"
}
tasks.named("check") {
    dependsOn("uiTest")
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
