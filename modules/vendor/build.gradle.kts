plugins {
    `java-library`
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
    into(layout.buildDirectory.dir("resources/main/META-INF/resources/vendor"))

    // Ensure task re-runs if output directory is missing
    outputs.upToDateWhen { layout.buildDirectory.dir("resources/main/META-INF/resources/vendor").get().asFile.exists() }
}

tasks.named("processResources") {
    dependsOn(copyDistToResources)
}
