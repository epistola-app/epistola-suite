rootProject.name = "epistola-suite"

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent {
                snapshotsOnly()
            }
        }
        // Spring milestone repo for spring-ai 2.0-Mx (used by :modules:epistola-mcp)
        maven("https://repo.spring.io/milestone") {
            mavenContent {
                releasesOnly()
            }
        }
    }
}

include(":apps:epistola")
include(":modules:epistola-core")
include(":modules:loadtest")
include(":modules:editor")
include(":modules:rest-api")
include(":modules:generation")
include(":modules:feedback")
include(":modules:testing")
include(":modules:epistola-mcp")
include(":modules:epistola-support")
