plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.3")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
}

val verifyFrontendBuild by tasks.registering {
    description = "Verifies that the frontend build output exists"
    group = "verification"

    doLast {
        val distDir = file("dist")
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

val copyDistToResources by tasks.registering(Copy::class) {
    dependsOn(verifyFrontendBuild)
    from("dist")
    into(layout.buildDirectory.dir("resources/main/META-INF/resources/editor"))

    outputs.upToDateWhen {
        layout.buildDirectory.dir("resources/main/META-INF/resources/editor").get().asFile.exists()
    }
}

tasks.named("processResources") {
    dependsOn(copyDistToResources)
}
