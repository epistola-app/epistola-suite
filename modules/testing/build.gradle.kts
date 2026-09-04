plugins {
    id("epistola-kotlin-conventions")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    api(project(":modules:epistola-core"))

    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-testcontainers")
    api("org.testcontainers:testcontainers-junit-jupiter")
    api("org.testcontainers:testcontainers-postgresql")
    api("org.jetbrains.kotlin:kotlin-test-junit5")

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // TemplateDatabase migrates the per-JVM template with Flyway directly.
    implementation("org.springframework.boot:spring-boot-starter-flyway")

    // TestSecurityContextConfiguration binds a principal per request, so it needs the servlet
    // filter API and Spring Security's context holder. `api` because the tests that @Import it are
    // in other projects and reference the same types.
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-security")

    // JDBI (needed by FakeDocumentGenerationExecutor and test contract version helpers)
    api(libs.jdbi.core)
    api(libs.jdbi.kotlin)

    // Jackson (needed by FakeDocumentGenerationExecutor)
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Micrometer (needed by FakeExecutorTestConfiguration)
    implementation("io.micrometer:micrometer-core")

    // Compile-visible (not just runtime): modules/testing ships a JUnit Platform
    // TestExecutionListener (the cross-cutting test-metrics harness) in its main source set.
    api("org.junit.platform:junit-platform-launcher")
    runtimeOnly("org.postgresql:postgresql")
}
