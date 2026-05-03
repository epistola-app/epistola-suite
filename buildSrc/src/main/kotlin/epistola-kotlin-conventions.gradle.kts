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

    jvmArgs(
        "-XX:+UseParallelGC",
        "-XX:TieredStopAtLevel=1",
        "-Xms256m",
        "-Xmx512m",
    )

    testLogging {
        events("passed", "skipped", "failed")
    }
}

val testSourceSet = sourceSets.named("test")

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
    jvmArgs("-XX:+UseParallelGC", "-XX:TieredStopAtLevel=1", "-Xms256m", "-Xmx512m")
    testLogging { events("passed", "skipped", "failed") }
    filter { isFailOnNoMatchingTests = false }
}

tasks.register<Test>("uiTest") {
    description = "Runs UI tests (Playwright + Spring + Testcontainers)"
    group = "verification"
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform { includeTags("ui") }
    jvmArgs("-XX:+UseParallelGC", "-XX:TieredStopAtLevel=1", "-Xms256m", "-Xmx512m")
    testLogging { events("passed", "skipped", "failed") }
    filter { isFailOnNoMatchingTests = false }
}

// Perf tests — opt-in via `@Tag("perf")`. Excluded from `integrationTest` so the
// regular IT cycle stays fast. Run on demand with `:perfTest --tests ...`.
// Bigger heap + longer per-test timeout because perf tests bulk-insert lots of
// rows and run multiple parallel virtual threads against Postgres.
tasks.register<Test>("perfTest") {
    description = "Runs performance tests (Spring + Testcontainers, opt-in)"
    group = "verification"
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform { includeTags("perf") }
    jvmArgs("-XX:+UseParallelGC", "-Xms512m", "-Xmx2g")
    timeout.set(Duration.ofMinutes(15))
    testLogging { events("passed", "skipped", "failed", "standardOut") }
    filter { isFailOnNoMatchingTests = false }
}
