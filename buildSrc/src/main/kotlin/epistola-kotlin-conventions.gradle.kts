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
        excludeTags("ui")
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
