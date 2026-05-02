plugins {
    `java-library`
    id("epistola-kotlin-conventions")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.6")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
}

val verifyFrontendBuild by tasks.registering {
    description = "Verifies that the frontend build output exists"
    group = "verification"
    val distDir = project.file("dist")

    doLast {
        if (!distDir.exists() || !distDir.isDirectory) {
            throw GradleException(
                """
                Frontend build output not found at: ${distDir.absolutePath}

                Please run the frontend build first:
                  pnpm install && pnpm build
                """.trimIndent(),
            )
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(verifyFrontendBuild)
    from("dist") {
        into("META-INF/resources/editor")
    }
}
