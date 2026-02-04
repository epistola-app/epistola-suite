plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
    id("org.jetbrains.kotlinx.kover")
}

// Use Spring Boot's dependency management without applying the plugin
// This gives us version management for Spring dependencies without making this a bootable app
the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    // Depend on epistola-core for business logic (tenant, template, document domains)
    api(project(":modules:epistola-core"))

    // Spring Boot - core dependencies
    implementation("org.springframework.boot:spring-boot-starter")

    // Jackson for JSON handling (required by LoadTestRun.testData: ObjectNode)
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()

    // JVM optimizations for faster test startup
    jvmArgs(
        "-XX:+UseParallelGC", // Parallel GC is faster for short-lived processes
        "-XX:TieredStopAtLevel=1", // Faster JVM startup (skip C2 compilation)
    )

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false // Keep output clean
    }
}
