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
    // Existing modules
    api(project(":modules:template-model"))
    api(project(":modules:generation"))
    api(project(":modules:rest-api")) // OpenAPI specs

    // Spring Boot - core dependencies for business logic
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Database - JDBI
    implementation(libs.jdbi.core)
    implementation(libs.jdbi.kotlin)
    implementation(libs.jdbi.postgres)
    implementation(libs.jdbi.jackson3)
    implementation(libs.jdbi.spring)
    runtimeOnly("org.postgresql:postgresql")

    // Validation
    implementation(libs.json.schema.validator)

    // UUID generation
    implementation(libs.uuid.creator)

    // Jackson 3 (tools.jackson) - version managed by Spring Boot BOM via spring-boot-starter-web
    // Note: Spring Boot 4+ uses Jackson 3 with tools.jackson groupId
    // These are already provided by spring-boot-starter-web transitively, but explicit for clarity

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
